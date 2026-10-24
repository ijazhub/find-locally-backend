package com.findlocally.api.repository;

import com.findlocally.api.model.*;
import com.findlocally.api.utility.*;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

@Repository
public class MessagesRepository {
    private final DbHelper db;
    private final BookingsRepository bookings;
    public MessagesRepository(DbHelper db, BookingsRepository bookings) { this.db = db; this.bookings = bookings; }
    public record Participant(UUID id, UUID customer, UUID owner, UUID booking) {}
    private static final String ACCESS = """
            FROM conversations c JOIN businesses b ON b.id = c.business_id
            WHERE (c.customer_id = :user OR b.owner_user_id = :user)
            """;
    public Participant participant(UUID user, UUID id, boolean lock) {
        var rows = db.getRows("SELECT c.*, b.owner_user_id " + ACCESS + " AND c.id = :id" + (lock ? " FOR UPDATE OF c" : ""),
                new MapSqlParameterSource("user", user).addValue("id", id),
                (rs, row) -> new Participant(id, rs.getObject("customer_id", UUID.class), rs.getObject("owner_user_id", UUID.class),
                        rs.getObject("booking_id", UUID.class)));
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public UUID start(UUID customer, UUID provider, UUID booking) {
        return db.getRows("""
                INSERT INTO conversations (customer_id, business_id, booking_id) VALUES (:customer, :provider, :booking)
                ON CONFLICT (customer_id, business_id) DO UPDATE SET booking_id = COALESCE(EXCLUDED.booking_id, conversations.booking_id)
                RETURNING id
                """, new MapSqlParameterSource("customer", customer).addValue("provider", provider).addValue("booking", booking),
                (rs, row) -> rs.getObject("id", UUID.class)).getFirst();
    }
    public Conversation conversation(UUID user, UUID id) {
        var rows = db.getRows("""
                SELECT c.*, b.owner_user_id, b.business_name, b.avatar_url AS business_avatar,
                    u.name AS customer_name, u.avatar_url AS customer_avatar
                FROM conversations c JOIN businesses b ON b.id = c.business_id JOIN users u ON u.id = c.customer_id
                WHERE c.id = :id AND (c.customer_id = :user OR b.owner_user_id = :user)
                """, new MapSqlParameterSource("id", id).addValue("user", user), (rs, row) -> {
            UUID customer = rs.getObject("customer_id", UUID.class);
            boolean isCustomer = user.equals(customer);
            var counterpart = new ConversationCounterpart()
                    .id(isCustomer ? rs.getObject("business_id", UUID.class) : customer)
                    .name(rs.getString(isCustomer ? "business_name" : "customer_name"));
            String avatar = rs.getString(isCustomer ? "business_avatar" : "customer_avatar");
            if (avatar != null) counterpart.setAvatarUrl(URI.create(avatar));
            return new Conversation().id(id).counterpart(counterpart);
        });
        if (rows.isEmpty()) return null;
        var result = rows.getFirst();
        var last = messages(id, null, 1);
        if (!last.isEmpty()) result.setLastMessage(last.getFirst());
        result.setUnreadCount(db.getSingleField("SELECT count(*)::integer FROM messages WHERE conversation_id = :id AND sender_id <> :user AND read_at IS NULL",
                new MapSqlParameterSource("id", id).addValue("user", user), Integer.class));
        var participant = participant(user, id, false);
        if (participant.booking() != null) result.setBooking(bookings.find(participant.customer(), participant.booking(), false));
        return result;
    }
    public ConversationPage page(UUID user, Pages page) {
        var params = new MapSqlParameterSource("user", user);
        long count = db.getSingleField("SELECT count(*) " + ACCESS, params, Long.class);
        var ids = db.getRows("""
                SELECT c.id
                """ + ACCESS + """
                 ORDER BY COALESCE((SELECT max(sent_at) FROM messages WHERE conversation_id = c.id), c.created_at) DESC, c.id
                 LIMIT :size OFFSET :offset
                """, params.addValue("size", page.size()).addValue("offset", page.offset()), (rs, row) -> rs.getObject("id", UUID.class));
        return new ConversationPage().page(page.page()).size(page.size()).totalElements(count).totalPages(page.totalPages(count))
                .content(ids.stream().map(id -> conversation(user, id)).toList());
    }
    public List<Message> messages(UUID id, OffsetDateTime before, int size) {
        return db.getRows("SELECT * FROM messages WHERE conversation_id = :id" + (before == null ? "" : " AND sent_at < :before")
                + " ORDER BY sent_at DESC, id DESC LIMIT :size",
                new MapSqlParameterSource("id", id).addValue("before", before).addValue("size", size), this::message);
    }
    public Message send(UUID id, UUID sender, String text) {
        return db.getRows("INSERT INTO messages (conversation_id, sender_id, text, sent_at) VALUES (:id, :sender, :text, clock_timestamp()) RETURNING *",
                new MapSqlParameterSource("id", id).addValue("sender", sender).addValue("text", text), this::message).getFirst();
    }
    public void read(UUID id, UUID user) {
        db.update("UPDATE messages SET read_at = clock_timestamp() WHERE conversation_id = :id AND sender_id <> :user AND read_at IS NULL",
                new MapSqlParameterSource("id", id).addValue("user", user));
    }
    private Message message(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Message().id(rs.getObject("id", UUID.class)).conversationId(rs.getObject("conversation_id", UUID.class))
                .senderId(rs.getObject("sender_id", UUID.class)).text(rs.getString("text"))
                .sentAt(rs.getObject("sent_at", OffsetDateTime.class)).readAt(rs.getObject("read_at", OffsetDateTime.class));
    }
}

