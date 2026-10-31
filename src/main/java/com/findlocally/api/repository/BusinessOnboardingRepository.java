package com.findlocally.api.repository;

import com.findlocally.api.model.*;
import com.findlocally.api.utility.DbHelper;
import java.net.URI;
import java.sql.Time;
import java.util.List;
import java.util.UUID;
import org. springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class BusinessOnboardingRepository {
    private final DbHelper dbHelper;
    private final ObjectMapper mapper;

    public BusinessOnboardingRepository(DbHelper dbHelper, ObjectMapper mapper) {
        this.dbHelper = dbHelper;
        this.mapper = mapper;
    }

    public record Draft(UUID id, String status, OnboardingState state) {}
    public record Upload(UUID id, String purpose, String objectKey, String contentType) {}

    public boolean isBusinessAccount(UUID ownerId) {
        return Boolean.TRUE.equals(dbHelper.getSingleField("SELECT EXISTS (SELECT 1 FROM users u JOIN user_roles r ON r.user_id=u.id AND r.role='BUSINESS' WHERE u.id = :id)",
                params(ownerId), Boolean.class));
    }

    public boolean create(UUID ownerId) {
        return dbHelper.insertWithoutKey("""
                INSERT INTO businesses (owner_user_id) VALUES (:id)
                ON CONFLICT (owner_user_id) DO NOTHING
                """, params(ownerId)) == 1;
    }

    /** Serialize draft writes on the business row so parallel step saves cannot overwrite progress. */
    public Draft find(UUID ownerId, boolean lock) {
        var rows = dbHelper.getRows("""
                SELECT id, status, verification_status, current_step, completed_steps, onboarding_data::text
                FROM businesses WHERE owner_user_id = :id AND EXISTS (SELECT 1 FROM user_roles r WHERE r.user_id=:id AND r.role='BUSINESS')
                """ + (lock ? " FOR UPDATE" : ""), params(ownerId), (rs, row) -> {
            var state = mapper.readValue(rs.getString("onboarding_data"), OnboardingState.class);
            String status = rs.getString("status");
            String verification = rs.getString("verification_status");
            state.setStatus(OnboardingState.StatusEnum.fromValue(
                    "DRAFT".equals(status) ? "DRAFT" : switch (verification) {
                        case "VERIFIED" -> "VERIFIED";
                        case "REJECTED" -> "REJECTED";
                        default -> "SUBMITTED";
                    }));
            state.setCurrentStep(OnboardingState.CurrentStepEnum.fromValue(rs.getString("current_step")));
            var array = rs.getArray("completed_steps");
            try {
                state.setCompletedSteps(java.util.Arrays.stream((String[]) array.getArray())
                        .map(OnboardingState.CompletedStepsEnum::fromValue).toList());
            } finally {
                array.free();
            }
            return new Draft(rs.getObject("id", UUID.class), status, state);
        });
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void saveProgress(UUID id, OnboardingState state) {
        String completed = "{" + String.join(",", state.getCompletedSteps().stream().map(Enum::name).toList()) + "}";
        dbHelper.update("""
                UPDATE businesses SET onboarding_data = CAST(:data AS jsonb),
                    current_step = :step, completed_steps = CAST(:completed AS text[])
                WHERE id = :id
                """, params(id).addValue("data", mapper.writeValueAsString(state))
                .addValue("step", state.getCurrentStep().getValue()).addValue("completed", completed));
    }

    public UUID findCategory(String slug) {
        return dbHelper.getSingleField("SELECT id FROM categories WHERE slug = :slug",
                new MapSqlParameterSource("slug", slug), UUID.class);
    }

    public void saveAbout(UUID id, UUID categoryId, OnboardingAbout input) {
        dbHelper.update("""
                UPDATE businesses SET owner_name = :owner, business_name = :name, category_id = :category, bio = :bio
                WHERE id = :id
                """, params(id).addValue("owner", input.getOwnerName()).addValue("name", input.getBusinessName())
                .addValue("category", categoryId).addValue("bio", input.getBio()));
    }

    public void saveLocation(UUID id, OnboardingLocation input) {
        var location = input.getLocation();
        dbHelper.update("""
                UPDATE businesses SET city = :city, neighbourhood = :area, lat = :lat, lng = :lng,
                    service_radius_km = :radius, work_mode = :mode WHERE id = :id
                """, params(id).addValue("city", input.getCity()).addValue("area", input.getNeighbourhood())
                .addValue("lat", location == null ? null : location.getLat())
                .addValue("lng", location == null ? null : location.getLng())
                .addValue("radius", input.getServiceRadiusKm()).addValue("mode", input.getWorkMode().getValue()));
    }

    public void saveBudget(UUID id, OnboardingServices input) {
        var budget = input.getBudgetRange();
        dbHelper.update("""
                UPDATE businesses SET budget_min_amount = :min, budget_max_amount = :max WHERE id = :id
                """, params(id).addValue("min", budget == null || budget.getMin() == null ? null : budget.getMin().getAmount())
                .addValue("max", budget == null || budget.getMax() == null ? null : budget.getMax().getAmount()));
    }

    public void saveAvailability(UUID id, AvailabilityInput input) {
        dbHelper.update("UPDATE businesses SET by_appointment_only = :appointment WHERE id = :id",
                params(id).addValue("appointment", Boolean.TRUE.equals(input.getByAppointmentOnly())));
        dbHelper.update("DELETE FROM business_hours WHERE business_id = :id", params(id));
        if (input.getWeekly() != null) {
            for (var day : input.getWeekly()) {
                boolean open = !Boolean.FALSE.equals(day.getOpen());
                dbHelper.insertWithoutKey("""
                        INSERT INTO business_hours (business_id, day, is_open, open_time, close_time)
                        VALUES (:id, :day, :open, :from, :to)
                        """, params(id).addValue("day", day.getDay().getValue()).addValue("open", open)
                        .addValue("from", open ? Time.valueOf(java.time.LocalTime.parse(day.getFrom())) : null)
                        .addValue("to", open ? Time.valueOf(java.time.LocalTime.parse(day.getTo())) : null));
            }
        }
        dbHelper.update("DELETE FROM business_blocked_dates WHERE business_id = :id", params(id));
        if (input.getBlockedDates() != null) {
            for (var date : input.getBlockedDates()) {
                dbHelper.insertWithoutKey("""
                        INSERT INTO business_blocked_dates (business_id, blocked_on) VALUES (:id, :date)
                        """, params(id).addValue("date", date));
            }
        }
    }

    public Upload findUpload(UUID ownerId, UUID uploadId) {
        var rows = dbHelper.getRows("""
                SELECT id, purpose, object_key, content_type FROM uploads
                WHERE id = :id AND user_id = :owner AND status = 'UPLOADED' AND (purpose IN ('GALLERY_PHOTO','GALLERY_VIDEO','ID_DOCUMENT') OR processing_state='READY')
                """, params(uploadId).addValue("owner", ownerId),
                (rs, row) -> new Upload(rs.getObject("id", UUID.class), rs.getString("purpose"),
                        rs.getString("object_key"), rs.getString("content_type")));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void saveWork(UUID id, String avatarUrl, String coverUrl, List<Upload> gallery, List<String> urls) {
        dbHelper.update("UPDATE businesses SET avatar_url = :avatar, cover_image_url = :cover WHERE id = :id",
                params(id).addValue("avatar", avatarUrl).addValue("cover", coverUrl));
        dbHelper.update("DELETE FROM business_media WHERE business_id = :id", params(id));
        for (int i = 0; i < gallery.size(); i++) {
            Upload upload = gallery.get(i);
            dbHelper.insertWithoutKey("""
                    INSERT INTO business_media (business_id, upload_id, type, url, sort_order)
                    VALUES (:id, :upload, :type, :url, :sort)
                    """, params(id).addValue("upload", upload.id())
                    .addValue("type", "GALLERY_VIDEO".equals(upload.purpose()) ? "VIDEO" : "PHOTO")
                    .addValue("url", urls.get(i)).addValue("sort", i));
        }
    }

    public void saveVerification(UUID id, OnboardingVerification input) {
        dbHelper.update("DELETE FROM business_verifications WHERE business_id = :id", params(id));
        dbHelper.insertWithoutKey("""
                INSERT INTO business_verifications (business_id, id_type, id_document_upload_id)
                VALUES (:id, :type, :upload)
                """, params(id).addValue("type", input.getIdType().getValue())
                .addValue("upload", input.getIdDocumentUploadId()));
    }

    public void publish(UUID id) {
        dbHelper.update("UPDATE businesses SET status = 'ACTIVE', current_step = 'DONE' WHERE id = :id", params(id));
    }

    public ProviderDetail preview(UUID id) {
        return dbHelper.getRows("""
                SELECT b.*, c.slug AS category FROM businesses b
                LEFT JOIN categories c ON c.id = b.category_id WHERE b.id = :id
                """, params(id), (rs, row) -> {
            var detail = new ProviderDetail();
            detail.setId(id);
            detail.setBusinessName(rs.getString("business_name"));
            detail.setOwnerName(rs.getString("owner_name"));
            detail.setCategory(rs.getString("category"));
            detail.setNeighbourhood(rs.getString("neighbourhood"));
            detail.setBio(rs.getString("bio"));
            detail.setAvatarUrl(uri(rs.getString("avatar_url")));
            detail.setCoverImageUrl(uri(rs.getString("cover_image_url")));
            detail.setRating(rs.getFloat("rating_avg"));
            detail.setReviewCount(rs.getInt("review_count"));
            String mode = rs.getString("work_mode");
            if (mode != null) detail.setWorkMode(ProviderDetail.WorkModeEnum.fromValue(mode));
            detail.setSaved(false);
            detail.setFollowed(false);
            return detail;
        }).getFirst();
    }

    private URI uri(String value) {
        return value == null ? null : URI.create(value);
    }

    private MapSqlParameterSource params(UUID id) {
        return new MapSqlParameterSource("id", id);
    }
}
