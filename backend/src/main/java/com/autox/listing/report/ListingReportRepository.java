package com.autox.listing.report;

import com.autox.common.UuidV7;
import com.autox.search.ListingSearchRepository;
import com.autox.user.UserRole;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Listing reports, in plain SQL like the message reports. */
@Repository
public class ListingReportRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ListingReportRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The listing's seller, if the public can see it now; empty otherwise. */
    public Optional<UUID> visibleListingSeller(UUID listingId) {
        return jdbc.query("SELECT l.seller_id FROM listing l WHERE l.id = :id AND " + ListingSearchRepository.visible("l"),
                new MapSqlParameterSource("id", listingId),
                (rs, row) -> rs.getObject(1, UUID.class)).stream().findFirst();
    }

    /** Files a report. False when this person has already reported this listing. */
    public boolean insert(UUID listingId, UUID reporterId, ListingReportReason reason, String note, Instant now) {
        return jdbc.update("""
                INSERT INTO listing_report (id, listing_id, reporter_id, reason, note, created_at)
                     VALUES (:id, :listing, :reporter, :reason, :note, :now)
                ON CONFLICT (listing_id, reporter_id) DO NOTHING
                """, new MapSqlParameterSource("id", UuidV7.generate())
                .addValue("listing", listingId).addValue("reporter", reporterId)
                .addValue("reason", reason.name()).addValue("note", note).addValue("now", Timestamp.from(now))) == 1;
    }

    public boolean reportedBy(UUID listingId, UUID reporterId) {
        Boolean reported = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM listing_report WHERE listing_id = :listing AND reporter_id = :reporter)",
                new MapSqlParameterSource("listing", listingId).addValue("reporter", reporterId), Boolean.class);
        return Boolean.TRUE.equals(reported);
    }

    /** Open reports, oldest first, with the listing as it is now and how many open reports it has. */
    public List<Row> open(int limit, long offset) {
        return jdbc.query("""
                SELECT r.id, r.reason, r.note, r.created_at, r.listing_id,
                       l.title, l.status, l.price_amount, l.price_currency_code, l.leasing_monthly,
                       s.id AS seller_id, s.display_name AS seller_name, s.role AS seller_role,
                       u.id AS reporter_id, u.display_name AS reporter_name, u.role AS reporter_role,
                       (SELECT count(*) FROM listing_report o WHERE o.listing_id = r.listing_id AND o.resolution IS NULL)
                           AS open_on_listing
                  FROM listing_report r
                  JOIN listing l ON l.id = r.listing_id
                  JOIN app_user s ON s.id = l.seller_id
                  JOIN app_user u ON u.id = r.reporter_id
                 WHERE r.resolution IS NULL
                 ORDER BY r.created_at, r.id
                 LIMIT :limit OFFSET :offset
                """, new MapSqlParameterSource("limit", limit).addValue("offset", offset),
                (rs, row) -> new Row(
                        rs.getObject("id", UUID.class),
                        ListingReportReason.valueOf(rs.getString("reason")),
                        rs.getString("note"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getObject("listing_id", UUID.class),
                        rs.getString("title"),
                        rs.getString("status"),
                        rs.getLong("price_amount"),
                        rs.getString("price_currency_code"),
                        rs.getObject("leasing_monthly", Long.class),
                        new Party(rs.getObject("seller_id", UUID.class), rs.getString("seller_name"),
                                UserRole.valueOf(rs.getString("seller_role"))),
                        new Party(rs.getObject("reporter_id", UUID.class), rs.getString("reporter_name"),
                                UserRole.valueOf(rs.getString("reporter_role"))),
                        rs.getInt("open_on_listing")));
    }

    public long countOpen() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM listing_report WHERE resolution IS NULL",
                new MapSqlParameterSource(), Long.class);
        return count == null ? 0 : count;
    }

    /** The listing a report is about, and whether it is still open. */
    public Optional<Target> target(UUID reportId) {
        return jdbc.query("SELECT listing_id, reason, resolution IS NULL AS open FROM listing_report WHERE id = :id",
                new MapSqlParameterSource("id", reportId),
                (rs, row) -> new Target(rs.getObject("listing_id", UUID.class),
                        ListingReportReason.valueOf(rs.getString("reason")), rs.getBoolean("open")))
                .stream().findFirst();
    }

    /** Closes every open report on the listing, the way the moderator decided. */
    public int resolveAll(UUID listingId, String resolution, UUID moderatorId, Instant now) {
        return jdbc.update("""
                UPDATE listing_report SET resolution = :resolution, resolved_at = :now, resolved_by = :moderator
                 WHERE listing_id = :listing AND resolution IS NULL
                """, new MapSqlParameterSource("listing", listingId).addValue("resolution", resolution)
                .addValue("moderator", moderatorId).addValue("now", Timestamp.from(now)));
    }

    public record Party(UUID id, String displayName, UserRole role) {
    }

    public record Row(UUID id, ListingReportReason reason, String note, Instant reportedAt, UUID listingId,
                      String title, String status, long priceAmount, String currencyCode, Long leasingMonthly,
                      Party seller, Party reporter, int openOnListing) {
    }

    public record Target(UUID listingId, ListingReportReason reason, boolean open) {
    }
}
