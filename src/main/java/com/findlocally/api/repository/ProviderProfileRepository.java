package com.findlocally.api.repository;

import com.findlocally.api.model.*;
import com.findlocally.api.utility.*;
import java.net.URI;
import java.sql.Types;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

@Repository
public class ProviderProfileRepository {
    private final DbHelper db;
    public ProviderProfileRepository(DbHelper db) { this.db = db; }
    public ProviderSocialProfile profile(UUID provider, UUID viewer, int year) {
        var rows = db.getRows("""
                SELECT b.*, c.name AS category_name, u.name AS user_name,
                    (SELECT count(*)::integer FROM follows WHERE business_id = b.id) AS followers,
                    EXISTS(SELECT 1 FROM follows WHERE business_id = b.id AND user_id = :viewer) AS followed,
                    (SELECT count(DISTINCT customer_id)::integer FROM bookings WHERE business_id = b.id AND status = 'COMPLETED') AS clients
                FROM businesses b JOIN categories c ON c.id = b.category_id JOIN users u ON u.id = b.owner_user_id
                WHERE b.id = :provider AND b.status = 'ACTIVE'
                """, new MapSqlParameterSource("provider", provider).addValue("viewer", viewer, Types.OTHER), (rs, row) -> {
            int created = rs.getObject("created_at", OffsetDateTime.class).getYear();
            Integer started = rs.getObject("started_year", Integer.class);
            var result = new ProviderSocialProfile().providerId(provider)
                    .displayName(rs.getString("owner_name") == null ? rs.getString("user_name") : rs.getString("owner_name"))
                    .businessName(rs.getString("business_name")).headline(rs.getString("headline")).role(rs.getString("category_name") + " · " + rs.getString("city"))
                    .bio(rs.getString("bio")).rating(rs.getBigDecimal("rating_avg")).reviewCount(rs.getInt("review_count"))
                    .memberSince(created).note(rs.getString("note")).area(rs.getString("neighbourhood") + ", " + rs.getString("city"))
                    .availabilityText(rs.getBoolean("by_appointment_only") ? "By appointment" : "See available time slots")
                    .followerCount(rs.getInt("followers")).followed(rs.getBoolean("followed"))
                    .stats(new ProviderSocialProfileStats().yearsWorking(Math.max(0, year - (started == null ? created : started)))
                            .happyClients(rs.getInt("clients")).averageRating(rs.getBigDecimal("rating_avg")));
            String avatar = rs.getString("avatar_url");
            if (avatar != null) result.setAvatarUrl(URI.create(avatar));
            return result;
        });
        if (rows.isEmpty()) return null;
        var result = rows.getFirst();
        result.setStudioNotes(db.getRows("SELECT * FROM studio_notes WHERE business_id = :id ORDER BY posted_at DESC, id LIMIT 20",
                new MapSqlParameterSource("id", provider), (rs, row) -> new ProviderSocialProfileStudioNotesInner()
                        .id(rs.getObject("id", UUID.class)).text(rs.getString("text")).postedAt(rs.getObject("posted_at", OffsetDateTime.class))));
        return result;
    }
    public MediaPage media(UUID business, String type, Pages page) {
        return media(business,type,page,false);
    }
    public MediaPage media(UUID business, String type, Pages page, boolean owner) {
        String where = " WHERE business_id = :id" + (owner ? "" : " AND processing_state='READY' AND publication_state='PUBLISHED'") + (type == null ? "" : " AND type = :type");
        var params = new MapSqlParameterSource("id", business).addValue("type", type);
        long count = db.getSingleField("SELECT count(*) FROM business_media" + where, params, Long.class);
        return new MediaPage().page(page.page()).size(page.size()).totalElements(count).totalPages(page.totalPages(count))
                .content(db.getRows("SELECT * FROM business_media" + where + " ORDER BY sort_order, created_at, id LIMIT :size OFFSET :offset",
                        params.addValue("size", page.size()).addValue("offset", page.offset()), this::mapMedia));
    }
    public MediaItem findMedia(UUID business, UUID upload) {
        var rows = db.getRows("SELECT * FROM business_media WHERE business_id = :business AND upload_id = :upload ORDER BY created_at, id LIMIT 1",
                new MapSqlParameterSource("business", business).addValue("upload", upload), this::mapMedia);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public MediaItem addMedia(UUID business, MediaRepository.Upload upload, String url, String caption) {
        return db.getRows("""
                INSERT INTO business_media (business_id, upload_id, type, url, caption)
                VALUES (:business, :upload, :type, :url, :caption) RETURNING *
                """, new MapSqlParameterSource("business", business).addValue("upload", upload.id())
                .addValue("type", upload.purpose().equals("GALLERY_VIDEO") ? "VIDEO" : "PHOTO")
                .addValue("url", url).addValue("caption", caption), this::mapMedia).getFirst();
    }
    public MediaItem ownedMedia(UUID business,UUID id) {
        var rows=db.getRows("SELECT * FROM business_media WHERE business_id=:business AND id=:id FOR UPDATE",
          new MapSqlParameterSource("business",business).addValue("id",id),this::mapMedia);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public void metadata(UUID business,UUID id,MediaUpdate input,String thumbnail) {
        db.update("""
          UPDATE business_media SET caption=COALESCE(:caption,caption),
            presentation_format=COALESCE(:format,presentation_format),publication_state=COALESCE(:publication,publication_state),
            cover_upload_id=COALESCE(:cover,cover_upload_id),thumbnail_url=COALESCE(:thumbnail,thumbnail_url)
          WHERE business_id=:business AND id=:id
          """,new MapSqlParameterSource("business",business).addValue("id",id).addValue("caption",input.getCaption())
          .addValue("format",input.getPresentationFormat()==null?null:input.getPresentationFormat().getValue())
          .addValue("publication",input.getPublicationState()==null?null:input.getPublicationState().getValue())
          .addValue("cover",input.getCoverUploadId()).addValue("thumbnail",thumbnail));
    }
    public int deleteMedia(UUID business, UUID id) {
        return db.update("DELETE FROM business_media WHERE id = :id AND business_id = :business",
                new MapSqlParameterSource("id", id).addValue("business", business));
    }
    private MediaItem mapMedia(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        var media = new MediaItem().id(rs.getObject("id", UUID.class)).type(MediaItem.TypeEnum.fromValue(rs.getString("type")))
                .url(URI.create(rs.getString("url"))).caption(rs.getString("caption")).durationSeconds(rs.getObject("duration_seconds", Integer.class));
        String thumb = rs.getString("thumbnail_url");
        if (thumb != null) media.setThumbnailUrl(URI.create(thumb));
        media.setPresentationFormat(MediaItem.PresentationFormatEnum.fromValue(rs.getString("presentation_format")));
        media.setProcessingState(MediaItem.ProcessingStateEnum.fromValue(rs.getString("processing_state")));
        media.setPublicationState(MediaItem.PublicationStateEnum.fromValue(rs.getString("publication_state")));
        media.setWidth(rs.getObject("width",Integer.class)); media.setHeight(rs.getObject("height",Integer.class));
        if (media.getProcessingState()!=MediaItem.ProcessingStateEnum.READY || media.getPublicationState()!=MediaItem.PublicationStateEnum.PUBLISHED) media.setUrl(null);
        return media;
    }
}

