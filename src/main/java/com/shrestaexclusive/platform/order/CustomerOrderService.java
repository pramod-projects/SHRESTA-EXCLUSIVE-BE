package com.shrestaexclusive.platform.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shrestaexclusive.platform.auth.AuthenticatedCustomer;
import com.shrestaexclusive.platform.order.CustomerOrderResponse.ContactSnapshot;
import com.shrestaexclusive.platform.order.CustomerOrderResponse.LineItem;
import com.shrestaexclusive.platform.order.CustomerOrderResponse.ShippingAddressSnapshot;
import com.shrestaexclusive.platform.order.CustomerOrderResponse.StatusEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerOrderService {

    private static final long FREE_DELIVERY_THRESHOLD_PAISE = 49_900L;
    private static final long STANDARD_DELIVERY_PAISE = 4_900L;
    private static final long EXPRESS_DELIVERY_PAISE = 14_900L;
    private static final long SAME_DAY_DELIVERY_PAISE = 29_900L;
    private static final Duration CHECKOUT_DRAFT_TTL = Duration.ofMinutes(15);
    private static final String DEFAULT_DRAFT_DELIVERY_MODE = "STANDARD";
    private static final DateTimeFormatter ORDER_DATE = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final TypeReference<Map<String, Object>> STRING_OBJECT_MAP = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public CustomerOrderService(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this(jdbcTemplate, objectMapper, Clock.systemUTC());
    }

    CustomerOrderService(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public CustomerOrderResponse placeOrder(AuthenticatedCustomer customer, CustomerOrderPlacementRequest request) {
        if (!request.acceptedTerms()) {
            throw new CustomerOrderPlacementException("Order terms must be accepted before placement.");
        }

        List<CartLine> normalizedLines = normalizePlacementLines(request.lines());
        String cartSignature = cartSignature(normalizedLines);
        UUID draftOrderId = requiredDraftOrderId(request.draftOrderId());
        Instant now = Instant.now(clock);
        validateDraftForPlacement(customer.customerId(), draftOrderId, cartSignature, now);

        Map<String, ProductSnapshot> productById = loadProducts(normalizedLines.stream()
                .map(CartLine::productId)
                .toList());

        long subtotalPaise = 0L;
        List<OrderLineSnapshot> orderLines = new ArrayList<>();
        for (CartLine line : normalizedLines) {
            ProductSnapshot product = productById.get(line.productId());
            if (product == null) {
                throw new CustomerOrderProductUnavailableException(line.productId());
            }
            long lineTotalPaise = product.pricePaise() * line.quantity();
            subtotalPaise += lineTotalPaise;
            orderLines.add(new OrderLineSnapshot(product, line.quantity(), lineTotalPaise));
        }

        String deliveryMode = normalizeEnum(request.deliveryMode());
        String paymentMethod = normalizeEnum(request.paymentMethod());
        long deliveryPaise = deliveryPaise(deliveryMode, subtotalPaise);
        long totalPaise = subtotalPaise + deliveryPaise;
        UUID orderId = UUID.randomUUID();
        String orderNumber = newOrderNumber();

        MapSqlParameterSource orderParameters = new MapSqlParameterSource()
                .addValue("id", orderId)
                .addValue("orderNumber", orderNumber)
                .addValue("customerId", customer.customerId())
                .addValue("customerEmail", customer.identityEmail())
                .addValue("subtotalPaise", subtotalPaise)
                .addValue("deliveryPaise", deliveryPaise)
                .addValue("totalPaise", totalPaise)
                .addValue("deliveryMode", deliveryMode)
                .addValue("paymentMethod", paymentMethod)
                .addValue("contactJson", json(request.contact()))
                .addValue("shippingAddressJson", json(request.shippingAddress()))
                .addValue("metadataJson", json(Map.of(
                        "source", "WEB_CHECKOUT",
                        "checkoutDraftOrderId", draftOrderId.toString(),
                        "cartSignature", cartSignature,
                        "customerDisplayName", customer.displayName(),
                        "sessionExpiresAt", customer.sessionExpiresAt().toString()
                )))
                .addValue("now", Timestamp.from(now));

        jdbcTemplate.update("""
                INSERT INTO customer_orders (
                    id, order_number, customer_id, customer_email, status, payment_status,
                    fulfillment_status, subtotal_paise, delivery_paise, total_paise,
                    delivery_mode, payment_method, contact_snapshot,
                    shipping_address_snapshot, metadata, placed_at, created_at, updated_at
                )
                VALUES (
                    :id, :orderNumber, :customerId, :customerEmail, 'PLACED', 'PENDING',
                    'PENDING', :subtotalPaise, :deliveryPaise, :totalPaise,
                    :deliveryMode, :paymentMethod, CAST(:contactJson AS jsonb),
                    CAST(:shippingAddressJson AS jsonb), CAST(:metadataJson AS jsonb),
                    :now, :now, :now
                )
                """, orderParameters);

        for (OrderLineSnapshot line : orderLines) {
            insertLine(orderId, line);
        }

        insertStatusEvent(orderId, "ORDER_STATUS", null, "PLACED", "CUSTOMER", customer.customerId().toString(), "Order placed from storefront checkout.", now);
        insertStatusEvent(orderId, "PAYMENT_STATUS", null, "PENDING", "SYSTEM", null, "Payment handoff is pending gateway integration.", now);
        insertStatusEvent(orderId, "FULFILLMENT_STATUS", null, "PENDING", "SYSTEM", null, "Fulfillment allocation pending.", now);
        convertDraft(customer.customerId(), draftOrderId, orderId, now);

        return findOrderForCustomer(customer.customerId(), orderNumber);
    }

    @Transactional
    public CustomerOrderDraftResponse createOrReuseDraft(AuthenticatedCustomer customer, CustomerOrderDraftRequest request) {
        List<CartLine> normalizedLines = normalizeDraftLines(request.lines());
        String cartSignature = cartSignature(normalizedLines);
        Instant now = Instant.now(clock);

        expireActiveDrafts(customer.customerId(), now);
        CustomerOrderDraftResponse existingDraft = findActiveDraftForCart(customer.customerId(), cartSignature, now);
        if (existingDraft != null) {
            return existingDraft;
        }

        invalidateActiveDraftsForChangedCart(customer.customerId(), cartSignature, now);

        Map<String, ProductSnapshot> productById = loadProducts(normalizedLines.stream()
                .map(CartLine::productId)
                .toList());

        long subtotalPaise = 0L;
        List<OrderLineSnapshot> orderLines = new ArrayList<>();
        for (CartLine line : normalizedLines) {
            ProductSnapshot product = productById.get(line.productId());
            if (product == null) {
                throw new CustomerOrderProductUnavailableException(line.productId());
            }
            long lineTotalPaise = product.pricePaise() * line.quantity();
            subtotalPaise += lineTotalPaise;
            orderLines.add(new OrderLineSnapshot(product, line.quantity(), lineTotalPaise));
        }

        long deliveryPaise = deliveryPaise(DEFAULT_DRAFT_DELIVERY_MODE, subtotalPaise);
        long totalPaise = subtotalPaise + deliveryPaise;
        UUID draftId = UUID.randomUUID();
        String draftNumber = newOrderNumber("SHRESTA-DRAFT-");
        Instant expiresAt = now.plus(CHECKOUT_DRAFT_TTL);

        jdbcTemplate.update("""
                INSERT INTO customer_order_drafts (
                    id, draft_number, customer_id, customer_email, status, cart_signature,
                    subtotal_paise, delivery_paise, total_paise, delivery_mode, metadata,
                    expires_at, created_at, updated_at
                )
                VALUES (
                    :id, :draftNumber, :customerId, :customerEmail, 'ACTIVE', :cartSignature,
                    :subtotalPaise, :deliveryPaise, :totalPaise, :deliveryMode, CAST(:metadataJson AS jsonb),
                    :expiresAt, :now, :now
                )
                """, new MapSqlParameterSource()
                .addValue("id", draftId)
                .addValue("draftNumber", draftNumber)
                .addValue("customerId", customer.customerId())
                .addValue("customerEmail", customer.identityEmail())
                .addValue("cartSignature", cartSignature)
                .addValue("subtotalPaise", subtotalPaise)
                .addValue("deliveryPaise", deliveryPaise)
                .addValue("totalPaise", totalPaise)
                .addValue("deliveryMode", DEFAULT_DRAFT_DELIVERY_MODE)
                .addValue("metadataJson", json(Map.of(
                        "source", "WEB_CART_PROCEED_TO_CHECKOUT",
                        "ttlMinutes", CHECKOUT_DRAFT_TTL.toMinutes(),
                        "customerDisplayName", customer.displayName(),
                        "sessionExpiresAt", customer.sessionExpiresAt().toString()
                )))
                .addValue("expiresAt", Timestamp.from(expiresAt))
                .addValue("now", Timestamp.from(now)));

        for (OrderLineSnapshot line : orderLines) {
            insertDraftLine(draftId, line);
        }

        return findDraftForCustomer(customer.customerId(), draftId);
    }

    @Transactional(readOnly = true)
    public List<CustomerOrderSummaryResponse> listOrdersForCustomer(UUID customerId) {
        return jdbcTemplate.query("""
                SELECT order_row.order_number,
                       order_row.status,
                       order_row.payment_status,
                       order_row.fulfillment_status,
                       order_row.currency,
                       order_row.total_paise,
                       order_row.delivery_mode,
                       order_row.payment_method,
                       order_row.placed_at,
                       COALESCE(SUM(item.quantity), 0)::int AS item_count
                FROM customer_orders order_row
                LEFT JOIN customer_order_items item ON item.order_id = order_row.id
                WHERE order_row.customer_id = :customerId
                GROUP BY order_row.id, order_row.order_number, order_row.status,
                         order_row.payment_status, order_row.fulfillment_status,
                         order_row.currency, order_row.total_paise, order_row.delivery_mode,
                         order_row.payment_method, order_row.placed_at
                ORDER BY order_row.placed_at DESC
                LIMIT 50
                """, new MapSqlParameterSource("customerId", customerId), (rs, rowNum) -> new CustomerOrderSummaryResponse(
                rs.getString("order_number"),
                rs.getString("status"),
                rs.getString("payment_status"),
                rs.getString("fulfillment_status"),
                rs.getString("currency"),
                rs.getLong("total_paise"),
                rs.getString("delivery_mode"),
                rs.getString("payment_method"),
                rs.getInt("item_count"),
                rs.getTimestamp("placed_at").toInstant()
        ));
    }

    @Transactional(readOnly = true)
    public CustomerOrderResponse findOrderForCustomer(UUID customerId, String orderNumber) {
        OrderRow order = jdbcTemplate.query("""
                SELECT id, order_number, customer_id, customer_email, status, payment_status,
                       fulfillment_status, currency, subtotal_paise, delivery_paise,
                       discount_paise, tax_paise, total_paise, delivery_mode, payment_method,
                       contact_snapshot::text AS contact_snapshot,
                       shipping_address_snapshot::text AS shipping_address_snapshot,
                       placed_at
                FROM customer_orders
                WHERE customer_id = :customerId AND order_number = :orderNumber
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("customerId", customerId)
                .addValue("orderNumber", orderNumber), CustomerOrderService::mapOrder).stream().findFirst()
                .orElseThrow(() -> new CustomerOrderNotFoundException(orderNumber));

        return new CustomerOrderResponse(
                order.orderNumber(),
                order.customerId().toString(),
                order.customerEmail(),
                order.status(),
                order.paymentStatus(),
                order.fulfillmentStatus(),
                order.currency(),
                order.subtotalPaise(),
                order.deliveryPaise(),
                order.discountPaise(),
                order.taxPaise(),
                order.totalPaise(),
                order.deliveryMode(),
                order.paymentMethod(),
                contact(order.contactSnapshotJson()),
                shippingAddress(order.shippingAddressSnapshotJson()),
                lines(order.id()),
                statusEvents(order.id()),
                order.placedAt()
        );
    }

    private CustomerOrderDraftResponse findActiveDraftForCart(UUID customerId, String cartSignature, Instant now) {
        return jdbcTemplate.query("""
                SELECT id, draft_number, customer_id, customer_email, status, cart_signature,
                       currency, subtotal_paise, delivery_paise, discount_paise, tax_paise,
                       total_paise, delivery_mode, expires_at, created_at
                FROM customer_order_drafts
                WHERE customer_id = :customerId
                  AND cart_signature = :cartSignature
                  AND status = 'ACTIVE'
                  AND expires_at > :now
                ORDER BY created_at DESC
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("customerId", customerId)
                .addValue("cartSignature", cartSignature)
                .addValue("now", Timestamp.from(now)), CustomerOrderService::mapDraft).stream()
                .findFirst()
                .map(this::draftResponse)
                .orElse(null);
    }

    private CustomerOrderDraftResponse findDraftForCustomer(UUID customerId, UUID draftId) {
        DraftRow draft = jdbcTemplate.query("""
                SELECT id, draft_number, customer_id, customer_email, status, cart_signature,
                       currency, subtotal_paise, delivery_paise, discount_paise, tax_paise,
                       total_paise, delivery_mode, expires_at, created_at
                FROM customer_order_drafts
                WHERE customer_id = :customerId AND id = :draftId
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("customerId", customerId)
                .addValue("draftId", draftId), CustomerOrderService::mapDraft).stream().findFirst()
                .orElseThrow(() -> new CustomerOrderPlacementException("Checkout order ID could not be found."));

        return draftResponse(draft);
    }

    private CustomerOrderDraftResponse draftResponse(DraftRow draft) {
        return new CustomerOrderDraftResponse(
                draft.id().toString(),
                draft.draftNumber(),
                draft.customerId().toString(),
                draft.customerEmail(),
                draft.status(),
                draft.cartSignature(),
                draft.currency(),
                draft.subtotalPaise(),
                draft.deliveryPaise(),
                draft.discountPaise(),
                draft.taxPaise(),
                draft.totalPaise(),
                draft.deliveryMode(),
                draft.expiresAt(),
                draft.createdAt(),
                draftLines(draft.id())
        );
    }

    private void expireActiveDrafts(UUID customerId, Instant now) {
        jdbcTemplate.update("""
                UPDATE customer_order_drafts
                SET status = 'EXPIRED',
                    invalidated_at = :now,
                    invalidation_reason = 'TTL_EXPIRED',
                    updated_at = :now
                WHERE customer_id = :customerId
                  AND status = 'ACTIVE'
                  AND expires_at <= :now
                """, new MapSqlParameterSource()
                .addValue("customerId", customerId)
                .addValue("now", Timestamp.from(now)));
    }

    private void invalidateActiveDraftsForChangedCart(UUID customerId, String cartSignature, Instant now) {
        jdbcTemplate.update("""
                UPDATE customer_order_drafts
                SET status = 'INVALIDATED',
                    invalidated_at = :now,
                    invalidation_reason = 'CART_CHANGED',
                    updated_at = :now
                WHERE customer_id = :customerId
                  AND status = 'ACTIVE'
                  AND cart_signature <> :cartSignature
                """, new MapSqlParameterSource()
                .addValue("customerId", customerId)
                .addValue("cartSignature", cartSignature)
                .addValue("now", Timestamp.from(now)));
    }

    private void validateDraftForPlacement(UUID customerId, UUID draftOrderId, String cartSignature, Instant now) {
        DraftValidationRow draft = jdbcTemplate.query("""
                SELECT id, status, cart_signature, expires_at
                FROM customer_order_drafts
                WHERE id = :draftOrderId AND customer_id = :customerId
                LIMIT 1
                FOR UPDATE
                """, new MapSqlParameterSource()
                .addValue("draftOrderId", draftOrderId)
                .addValue("customerId", customerId), (rs, rowNum) -> new DraftValidationRow(
                rs.getObject("id", UUID.class),
                rs.getString("status"),
                rs.getString("cart_signature"),
                rs.getTimestamp("expires_at").toInstant()
        )).stream().findFirst().orElseThrow(() -> new CustomerOrderPlacementException(
                "Checkout order ID is missing or no longer belongs to this customer."
        ));

        if ("ACTIVE".equals(draft.status()) && !draft.expiresAt().isAfter(now)) {
            jdbcTemplate.update("""
                    UPDATE customer_order_drafts
                    SET status = 'EXPIRED',
                        invalidated_at = :now,
                        invalidation_reason = 'TTL_EXPIRED',
                        updated_at = :now
                    WHERE id = :draftOrderId
                    """, new MapSqlParameterSource()
                    .addValue("draftOrderId", draftOrderId)
                    .addValue("now", Timestamp.from(now)));
            throw new CustomerOrderPlacementException("Checkout order ID expired. Return to cart and proceed again to get a fresh 15-minute order ID.");
        }

        if (!"ACTIVE".equals(draft.status())) {
            throw new CustomerOrderPlacementException("Checkout order ID is not active. Return to cart and proceed again.");
        }

        if (!cartSignature.equals(draft.cartSignature())) {
            jdbcTemplate.update("""
                    UPDATE customer_order_drafts
                    SET status = 'INVALIDATED',
                        invalidated_at = :now,
                        invalidation_reason = 'CART_CHANGED',
                        updated_at = :now
                    WHERE id = :draftOrderId
                    """, new MapSqlParameterSource()
                    .addValue("draftOrderId", draftOrderId)
                    .addValue("now", Timestamp.from(now)));
            throw new CustomerOrderPlacementException("Cart changed after checkout started. Return to cart and proceed again to create a new order ID.");
        }
    }

    private void convertDraft(UUID customerId, UUID draftOrderId, UUID orderId, Instant now) {
        int updated = jdbcTemplate.update("""
                UPDATE customer_order_drafts
                SET status = 'CONVERTED',
                    converted_order_id = :orderId,
                    updated_at = :now
                WHERE id = :draftOrderId
                  AND customer_id = :customerId
                  AND status = 'ACTIVE'
                """, new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("draftOrderId", draftOrderId)
                .addValue("customerId", customerId)
                .addValue("now", Timestamp.from(now)));
        if (updated != 1) {
            throw new CustomerOrderPlacementException("Checkout order ID could not be converted to a placed order.");
        }
    }

    private List<CartLine> normalizePlacementLines(List<CustomerOrderPlacementRequest.LineItem> lines) {
        return normalizeLines(lines.stream()
                .map(line -> new CartLine(line.productId(), line.quantity()))
                .toList());
    }

    private List<CartLine> normalizeDraftLines(List<CustomerOrderDraftRequest.LineItem> lines) {
        return normalizeLines(lines.stream()
                .map(line -> new CartLine(line.productId(), line.quantity()))
                .toList());
    }

    private List<CartLine> normalizeLines(List<CartLine> lines) {
        Map<String, Integer> merged = new LinkedHashMap<>();
        for (CartLine line : lines) {
            String productId = line.productId().trim();
            if (productId.isBlank()) {
                continue;
            }
            merged.merge(productId, line.quantity(), Integer::sum);
        }
        if (merged.isEmpty()) {
            throw new CustomerOrderPlacementException("At least one order item is required.");
        }
        return merged.entrySet().stream()
                .map(entry -> new CartLine(entry.getKey(), Math.min(99, entry.getValue())))
                .toList();
    }

    private Map<String, ProductSnapshot> loadProducts(List<String> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }

        return jdbcTemplate.query("""
                SELECT item.item_key, item.title, item.family_key, item.metadata::text AS metadata,
                       media.asset_key, media.asset_url, media.alt_text
                FROM storefront_home_items item
                JOIN storefront_home_sections section ON section.id = item.section_id
                LEFT JOIN media_assets media ON media.id = item.media_asset_id AND media.is_active = TRUE
                WHERE item.is_active = TRUE
                  AND section.is_active = TRUE
                  AND section.section_type = 'product_grid'
                  AND item.item_key IN (:productIds)
                """, new MapSqlParameterSource("productIds", productIds), (rs, rowNum) -> {
            Map<String, Object> metadata = jsonObject(rs.getString("metadata"));
            String itemKey = rs.getString("item_key");
            return new ProductSnapshot(
                    itemKey,
                    requiredString(metadata, "sku", itemKey),
                    requiredString(metadata, "slug", itemKey),
                    rs.getString("title"),
                    rs.getString("family_key"),
                    requiredString(metadata, "productType", "unknown"),
                    requiredLong(metadata, "pricePaise", itemKey),
                    longValue(metadata, "compareAtPricePaise"),
                    rs.getString("asset_key"),
                    rs.getString("asset_url"),
                    rs.getString("alt_text"),
                    metadata
            );
        }).stream().collect(Collectors.toMap(ProductSnapshot::productId, Function.identity()));
    }

    private void insertLine(UUID orderId, OrderLineSnapshot line) {
        ProductSnapshot product = line.product();
        jdbcTemplate.update("""
                INSERT INTO customer_order_items (
                    order_id, product_item_key, product_sku, product_slug, product_name,
                    family_key, product_type, quantity, unit_price_paise, compare_at_price_paise,
                    line_total_paise, media_asset_key, media_url, media_alt_text, metadata
                )
                VALUES (
                    :orderId, :productItemKey, :productSku, :productSlug, :productName,
                    :familyKey, :productType, :quantity, :unitPricePaise, :compareAtPricePaise,
                    :lineTotalPaise, :mediaAssetKey, :mediaUrl, :mediaAltText, CAST(:metadataJson AS jsonb)
                )
                """, new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("productItemKey", product.productId())
                .addValue("productSku", product.sku())
                .addValue("productSlug", product.slug())
                .addValue("productName", product.name())
                .addValue("familyKey", product.familyKey())
                .addValue("productType", product.productType())
                .addValue("quantity", line.quantity())
                .addValue("unitPricePaise", product.pricePaise())
                .addValue("compareAtPricePaise", product.compareAtPricePaise())
                .addValue("lineTotalPaise", line.lineTotalPaise())
                .addValue("mediaAssetKey", product.mediaAssetKey())
                .addValue("mediaUrl", product.mediaUrl())
                .addValue("mediaAltText", product.mediaAltText())
                .addValue("metadataJson", json(Map.of("backendProductMetadata", product.metadata()))));
    }

    private void insertDraftLine(UUID draftId, OrderLineSnapshot line) {
        ProductSnapshot product = line.product();
        jdbcTemplate.update("""
                INSERT INTO customer_order_draft_items (
                    order_draft_id, product_item_key, product_sku, product_slug, product_name,
                    family_key, product_type, quantity, unit_price_paise, compare_at_price_paise,
                    line_total_paise, media_asset_key, media_url, media_alt_text, metadata
                )
                VALUES (
                    :draftId, :productItemKey, :productSku, :productSlug, :productName,
                    :familyKey, :productType, :quantity, :unitPricePaise, :compareAtPricePaise,
                    :lineTotalPaise, :mediaAssetKey, :mediaUrl, :mediaAltText, CAST(:metadataJson AS jsonb)
                )
                """, new MapSqlParameterSource()
                .addValue("draftId", draftId)
                .addValue("productItemKey", product.productId())
                .addValue("productSku", product.sku())
                .addValue("productSlug", product.slug())
                .addValue("productName", product.name())
                .addValue("familyKey", product.familyKey())
                .addValue("productType", product.productType())
                .addValue("quantity", line.quantity())
                .addValue("unitPricePaise", product.pricePaise())
                .addValue("compareAtPricePaise", product.compareAtPricePaise())
                .addValue("lineTotalPaise", line.lineTotalPaise())
                .addValue("mediaAssetKey", product.mediaAssetKey())
                .addValue("mediaUrl", product.mediaUrl())
                .addValue("mediaAltText", product.mediaAltText())
                .addValue("metadataJson", json(Map.of("backendProductMetadata", product.metadata()))));
    }

    private void insertStatusEvent(UUID orderId, String eventType, String fromStatus, String toStatus, String actorType, String actorId, String note, Instant createdAt) {
        jdbcTemplate.update("""
                INSERT INTO customer_order_status_events (
                    order_id, event_type, from_status, to_status, actor_type, actor_id, note, created_at
                )
                VALUES (
                    :orderId, :eventType, :fromStatus, :toStatus, :actorType, :actorId, :note, :createdAt
                )
                """, new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("eventType", eventType)
                .addValue("fromStatus", fromStatus)
                .addValue("toStatus", toStatus)
                .addValue("actorType", actorType)
                .addValue("actorId", actorId)
                .addValue("note", note)
                .addValue("createdAt", Timestamp.from(createdAt)));
    }

    private List<LineItem> lines(UUID orderId) {
        return jdbcTemplate.query("""
                SELECT product_item_key, product_sku, product_slug, product_name, family_key,
                       product_type, quantity, unit_price_paise, line_total_paise,
                       media_asset_key, media_url, media_alt_text
                FROM customer_order_items
                WHERE order_id = :orderId
                ORDER BY created_at, product_name
                """, new MapSqlParameterSource("orderId", orderId), (rs, rowNum) -> new LineItem(
                rs.getString("product_item_key"),
                rs.getString("product_sku"),
                rs.getString("product_slug"),
                rs.getString("product_name"),
                rs.getString("family_key"),
                rs.getString("product_type"),
                rs.getInt("quantity"),
                rs.getLong("unit_price_paise"),
                rs.getLong("line_total_paise"),
                rs.getString("media_asset_key"),
                rs.getString("media_url"),
                rs.getString("media_alt_text")
        ));
    }

    private List<LineItem> draftLines(UUID draftId) {
        return jdbcTemplate.query("""
                SELECT product_item_key, product_sku, product_slug, product_name, family_key,
                       product_type, quantity, unit_price_paise, line_total_paise,
                       media_asset_key, media_url, media_alt_text
                FROM customer_order_draft_items
                WHERE order_draft_id = :draftId
                ORDER BY created_at, product_name
                """, new MapSqlParameterSource("draftId", draftId), (rs, rowNum) -> new LineItem(
                rs.getString("product_item_key"),
                rs.getString("product_sku"),
                rs.getString("product_slug"),
                rs.getString("product_name"),
                rs.getString("family_key"),
                rs.getString("product_type"),
                rs.getInt("quantity"),
                rs.getLong("unit_price_paise"),
                rs.getLong("line_total_paise"),
                rs.getString("media_asset_key"),
                rs.getString("media_url"),
                rs.getString("media_alt_text")
        ));
    }

    private List<StatusEvent> statusEvents(UUID orderId) {
        return jdbcTemplate.query("""
                SELECT event_type, from_status, to_status, actor_type, note, created_at
                FROM customer_order_status_events
                WHERE order_id = :orderId
                ORDER BY created_at, id
                """, new MapSqlParameterSource("orderId", orderId), (rs, rowNum) -> new StatusEvent(
                rs.getString("event_type"),
                rs.getString("from_status"),
                rs.getString("to_status"),
                rs.getString("actor_type"),
                rs.getString("note"),
                rs.getTimestamp("created_at").toInstant()
        ));
    }

    private ContactSnapshot contact(String json) {
        CustomerOrderPlacementRequest.Contact contact = read(json, CustomerOrderPlacementRequest.Contact.class);
        return new ContactSnapshot(contact.email(), contact.phone());
    }

    private ShippingAddressSnapshot shippingAddress(String json) {
        CustomerOrderPlacementRequest.ShippingAddress address = read(json, CustomerOrderPlacementRequest.ShippingAddress.class);
        return new ShippingAddressSnapshot(
                address.fullName(),
                address.phone(),
                address.addressLine1(),
                address.addressLine2(),
                address.landmark(),
                address.city(),
                address.state(),
                address.postalCode(),
                address.country(),
                address.addressType()
        );
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new CustomerOrderPlacementException("Stored order snapshot could not be read.");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new CustomerOrderPlacementException("Order payload could not be serialized.");
        }
    }

    private Map<String, Object> jsonObject(String json) {
        try {
            return objectMapper.readValue(json, STRING_OBJECT_MAP);
        } catch (JsonProcessingException exception) {
            throw new CustomerOrderPlacementException("Product metadata is invalid.");
        }
    }

    private String newOrderNumber() {
        return newOrderNumber("SHRESTA-");
    }

    private String newOrderNumber(String prefix) {
        byte[] bytes = new byte[5];
        secureRandom.nextBytes(bytes);
        return prefix + ORDER_DATE.format(Instant.now(clock)) + "-" + HexFormat.of().formatHex(bytes).toUpperCase(Locale.ROOT);
    }

    private String cartSignature(List<CartLine> lines) {
        String canonicalCart = lines.stream()
                .sorted(Comparator.comparing(CartLine::productId))
                .map(line -> line.productId() + ":" + line.quantity())
                .collect(Collectors.joining("|"));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonicalCart.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new CustomerOrderPlacementException("Checkout cart signature could not be created.");
        }
    }

    private long deliveryPaise(String deliveryMode, long subtotalPaise) {
        return switch (deliveryMode) {
            case "STANDARD" -> subtotalPaise >= FREE_DELIVERY_THRESHOLD_PAISE ? 0L : STANDARD_DELIVERY_PAISE;
            case "EXPRESS" -> EXPRESS_DELIVERY_PAISE;
            case "SAME_DAY" -> SAME_DAY_DELIVERY_PAISE;
            default -> throw new CustomerOrderPlacementException("Unsupported delivery mode.");
        };
    }

    private static OrderRow mapOrder(ResultSet rs, int rowNum) throws SQLException {
        return new OrderRow(
                rs.getObject("id", UUID.class),
                rs.getString("order_number"),
                rs.getObject("customer_id", UUID.class),
                rs.getString("customer_email"),
                rs.getString("status"),
                rs.getString("payment_status"),
                rs.getString("fulfillment_status"),
                rs.getString("currency"),
                rs.getLong("subtotal_paise"),
                rs.getLong("delivery_paise"),
                rs.getLong("discount_paise"),
                rs.getLong("tax_paise"),
                rs.getLong("total_paise"),
                rs.getString("delivery_mode"),
                rs.getString("payment_method"),
                rs.getString("contact_snapshot"),
                rs.getString("shipping_address_snapshot"),
                rs.getTimestamp("placed_at").toInstant()
        );
    }

    private static DraftRow mapDraft(ResultSet rs, int rowNum) throws SQLException {
        return new DraftRow(
                rs.getObject("id", UUID.class),
                rs.getString("draft_number"),
                rs.getObject("customer_id", UUID.class),
                rs.getString("customer_email"),
                rs.getString("status"),
                rs.getString("cart_signature"),
                rs.getString("currency"),
                rs.getLong("subtotal_paise"),
                rs.getLong("delivery_paise"),
                rs.getLong("discount_paise"),
                rs.getLong("tax_paise"),
                rs.getLong("total_paise"),
                rs.getString("delivery_mode"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private static UUID requiredDraftOrderId(String draftOrderId) {
        if (draftOrderId == null || draftOrderId.isBlank()) {
            throw new CustomerOrderPlacementException("Checkout order ID is required. Return to cart and proceed to checkout again.");
        }
        try {
            return UUID.fromString(draftOrderId.trim());
        } catch (IllegalArgumentException exception) {
            throw new CustomerOrderPlacementException("Checkout order ID is invalid. Return to cart and proceed to checkout again.");
        }
    }

    private static String normalizeEnum(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String requiredString(Map<String, Object> metadata, String key, String productId) {
        Object value = metadata.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new CustomerOrderProductUnavailableException(productId);
    }

    private static long requiredLong(Map<String, Object> metadata, String key, String productId) {
        long value = longValue(metadata, key);
        if (value > 0) {
            return value;
        }
        throw new CustomerOrderProductUnavailableException(productId);
    }

    private static long longValue(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Long.parseLong(text);
        }
        return 0L;
    }

    private record ProductSnapshot(
            String productId,
            String sku,
            String slug,
            String name,
            String familyKey,
            String productType,
            long pricePaise,
            long compareAtPricePaise,
            String mediaAssetKey,
            String mediaUrl,
            String mediaAltText,
            Map<String, Object> metadata
    ) {
    }

    private record CartLine(
            String productId,
            int quantity
    ) {
    }

    private record OrderLineSnapshot(
            ProductSnapshot product,
            int quantity,
            long lineTotalPaise
    ) {
    }

    private record OrderRow(
            UUID id,
            String orderNumber,
            UUID customerId,
            String customerEmail,
            String status,
            String paymentStatus,
            String fulfillmentStatus,
            String currency,
            long subtotalPaise,
            long deliveryPaise,
            long discountPaise,
            long taxPaise,
            long totalPaise,
            String deliveryMode,
            String paymentMethod,
            String contactSnapshotJson,
            String shippingAddressSnapshotJson,
            Instant placedAt
    ) {
    }

    private record DraftRow(
            UUID id,
            String draftNumber,
            UUID customerId,
            String customerEmail,
            String status,
            String cartSignature,
            String currency,
            long subtotalPaise,
            long deliveryPaise,
            long discountPaise,
            long taxPaise,
            long totalPaise,
            String deliveryMode,
            Instant expiresAt,
            Instant createdAt
    ) {
    }

    private record DraftValidationRow(
            UUID id,
            String status,
            String cartSignature,
            Instant expiresAt
    ) {
    }
}
