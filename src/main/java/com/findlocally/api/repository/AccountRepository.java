package com.findlocally.api.repository;

import com.findlocally.api.model.*;
import com.findlocally.api.utility.DbHelper;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {
    private final DbHelper dbHelper;

    public AccountRepository(DbHelper dbHelper) { this.dbHelper = dbHelper; }

    public UserProfile profile(UUID user) {
        var rows = dbHelper.getRows("""
                SELECT u.*, b.id AS business_id, b.status AS business_status, b.verification_status,
                    ARRAY(SELECT role FROM user_roles WHERE user_id = u.id ORDER BY role) AS roles,
                    (SELECT count(*)::integer FROM notifications WHERE user_id = u.id AND read_at IS NULL) AS unread_notifications,
                    (SELECT count(*)::integer FROM messages m JOIN conversations c ON c.id = m.conversation_id
                     JOIN businesses cb ON cb.id = c.business_id
                     WHERE (c.customer_id = u.id OR cb.owner_user_id = u.id)
                       AND m.sender_id <> u.id AND m.read_at IS NULL) AS unread_messages
                FROM users u LEFT JOIN businesses b ON b.owner_user_id = u.id WHERE u.id = :user
                """, params(user), (rs, row) -> {
            var profile = new UserProfile().id(user).name(rs.getString("name")).phone(rs.getString("phone"))
                    .email(rs.getString("email")).businessId(rs.getObject("business_id", UUID.class))
                    .unreadMessages(rs.getInt("unread_messages")).unreadNotifications(rs.getInt("unread_notifications"));
            String avatar = rs.getString("avatar_url");
            if (avatar != null) profile.setAvatarUrl(URI.create(avatar));
            var roles = rs.getArray("roles");
            try {
                profile.setRoles(Arrays.stream((String[]) roles.getArray()).map(UserProfile.RolesEnum::fromValue).toList());
            } finally { roles.free(); }
            if (profile.getRoles().size() != 1) throw new IllegalStateException("Account type audit required");
            profile.setAccountType(AccountType.fromValue(profile.getRoles().getFirst().getValue()));
            String onboarding = profile.getAccountType() == AccountType.CUSTOMER ? "NOT_APPLICABLE"
                    : profile.getBusinessId() == null ? "NOT_STARTED"
                    : "DRAFT".equals(rs.getString("business_status")) ? "DRAFT"
                    : "PENDING".equals(rs.getString("verification_status")) ? "SUBMITTED" : rs.getString("verification_status");
            profile.setOnboardingStatus(UserProfile.OnboardingStatusEnum.fromValue(onboarding));
            return profile;
        });
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Serialize address writes while allowing booking inserts to acquire their foreign-key locks. */
    public boolean lockUser(UUID user) {
        return dbHelper.getSingleField("SELECT id FROM users WHERE id = :user FOR NO KEY UPDATE", params(user), UUID.class) != null;
    }

    public void updateProfile(UUID user, UserUpdate input, String avatarUrl) {
        dbHelper.update("""
                UPDATE users SET name = COALESCE(:name, name), email = COALESCE(:email, email),
                    avatar_url = COALESCE(:avatar, avatar_url) WHERE id = :user
                """, params(user).addValue("name", input.getName()).addValue("email", input.getEmail()).addValue("avatar", avatarUrl));
    }

    public String avatarKey(UUID user, UUID upload) {
        return dbHelper.getSingleField("""
                SELECT object_key FROM uploads WHERE id = :upload AND user_id = :user
                    AND purpose = 'AVATAR' AND status = 'UPLOADED' AND processing_state='READY'
                """, params(user).addValue("upload", upload), String.class);
    }

    public List<Address> addresses(UUID user) {
        return dbHelper.getRows("SELECT * FROM addresses WHERE user_id = :user ORDER BY is_default DESC, created_at, id",
                params(user), this::mapAddress);
    }

    public Address address(UUID user, UUID id) {
        var rows = dbHelper.getRows("SELECT * FROM addresses WHERE user_id = :user AND id = :id",
                params(user).addValue("id", id), this::mapAddress);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void clearDefault(UUID user) {
        dbHelper.update("UPDATE addresses SET is_default = false WHERE user_id = :user AND is_default", params(user));
    }

    public Address createAddress(UUID user, AddressInput input) {
        return dbHelper.getRows("""
                INSERT INTO addresses (user_id, label, line1, line2, neighbourhood, city, state, pincode, lat, lng, is_default)
                VALUES (:user, :label, :line1, :line2, :area, :city, :state, :pincode, :lat, :lng, :default)
                RETURNING *
                """, addressParams(user, input), this::mapAddress).getFirst();
    }

    public Address updateAddress(UUID user, UUID id, AddressInput input) {
        var rows = dbHelper.getRows("""
                UPDATE addresses SET label = :label, line1 = :line1, line2 = :line2, neighbourhood = :area,
                    city = :city, state = :state, pincode = :pincode, lat = :lat, lng = :lng, is_default = :default
                WHERE user_id = :user AND id = :id RETURNING *
                """, addressParams(user, input).addValue("id", id), this::mapAddress);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public int deleteAddress(UUID user, UUID id) {
        return dbHelper.update("DELETE FROM addresses WHERE user_id = :user AND id = :id", params(user).addValue("id", id));
    }

    private MapSqlParameterSource addressParams(UUID user, AddressInput input) {
        var point = input.getLocation();
        return params(user).addValue("label", input.getLabel()).addValue("line1", input.getLine1())
                .addValue("line2", input.getLine2()).addValue("area", input.getNeighbourhood())
                .addValue("city", input.getCity()).addValue("state", input.getState()).addValue("pincode", input.getPincode())
                .addValue("lat", point == null ? null : point.getLat()).addValue("lng", point == null ? null : point.getLng())
                .addValue("default", Boolean.TRUE.equals(input.getIsDefault()));
    }

    private Address mapAddress(ResultSet rs, int row) throws SQLException {
        var address = new Address().id(rs.getObject("id", UUID.class)).label(rs.getString("label"))
                .line1(rs.getString("line1")).line2(rs.getString("line2")).neighbourhood(rs.getString("neighbourhood"))
                .city(rs.getString("city")).state(rs.getString("state")).pincode(rs.getString("pincode"))
                .isDefault(rs.getBoolean("is_default"));
        Double lat = rs.getObject("lat", Double.class), lng = rs.getObject("lng", Double.class);
        if (lat != null && lng != null) address.setLocation(new GeoPoint().lat(lat).lng(lng));
        return address;
    }

    private MapSqlParameterSource params(UUID user) { return new MapSqlParameterSource("user", user); }
}

