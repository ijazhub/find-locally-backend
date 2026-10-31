package com.findlocally.api;

import com.findlocally.api.model.*;
import com.findlocally.api.service.*;
import com.findlocally.api.repository.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** Runs the real migration chain in an isolated schema on an explicitly supplied test database. */
@EnabledIfSystemProperty(named="negotiation.postgres.tests",matches="true")
@SpringBootTest(properties={"spring.flyway.enabled=true","spring.flyway.schemas=negotiation_verification",
        "spring.datasource.hikari.schema=negotiation_verification",
        "spring.sql.init.mode=never","app.bookings.expiry-delay-ms=3600000"})
class NegotiationPersistenceTests {
    @TestConfiguration static class FixedTime {
        @Bean @Primary Clock testClock() { return Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"),ZoneOffset.UTC); }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired BookingsService bookings;
    @Autowired BusinessBookingsService providerBookings;
    @Autowired NegotiationService negotiation;
    @Autowired BookingExpiry expiry;
    @Autowired BusinessOnboardingService onboarding;
    @Autowired BusinessDashboardService dashboard;
    @Autowired ProviderProfileService profiles;
    @Autowired CartService cart;
    private UUID customer, other, owner, business, service;

    @BeforeEach void fixture() {
        customer=UUID.randomUUID();other=UUID.randomUUID();owner=UUID.randomUUID();business=UUID.randomUUID();service=UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            for(UUID user:List.of(customer,other,owner)) {
                jdbc.update("INSERT INTO users(id,email,name) VALUES(?,?,?)",user,user+"@test.example","Test Owner");
                jdbc.update("INSERT INTO user_roles(user_id,role) VALUES(?,?)",user,user.equals(owner)?"BUSINESS":"CUSTOMER");
            }
            UUID category=jdbc.queryForObject("SELECT id FROM categories LIMIT 1",UUID.class);
            jdbc.update("""
                INSERT INTO businesses(id,owner_user_id,status,category_id,business_name,owner_name,city,neighbourhood,by_appointment_only)
                VALUES(?,?,'ACTIVE',?,'Studio','Owner','Kolkata','Salt Lake',true)
                """,business,owner,category);
            jdbc.update("INSERT INTO services(id,business_id,name,duration_minutes,price_amount) VALUES(?,?,'Original Service',60,50000)",service,business);
        });
        sign(customer);
    }
    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }
    private void sign(UUID user) { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user.toString(),null,List.of())); }
    private BookingCreate request() { return new BookingCreate().providerId(business).serviceId(service)
            .startsAt(OffsetDateTime.parse("2026-11-03T10:00:00+05:30")).fulfilment(Fulfilment.AT_STUDIO).proposedPrice(new Money(45000L,"INR")); }
    private Booking create() { sign(customer); return bookings.createBooking(UUID.randomUUID(),request()); }
    private Booking quote(UUID booking,long amount,UUID key) { sign(owner); return negotiation.issue(booking,key,new QuotationInput().price(new Money(amount,"INR"))); }
    private Quotation current(Booking booking) { return booking.getQuotations().getLast(); }
    private QuotationResponse response(Quotation quote) { return new QuotationResponse().quotationId(quote.getId()).version(quote.getVersion()).decision(QuotationResponse.DecisionEnum.ACCEPT); }
    private void conflict(Runnable action) { assertEquals(409,assertThrows(ResponseStatusException.class,action::run).getStatusCode().value()); }

    @Test void quoteRevisionsExplicitApprovalSnapshotsAndRetries() {
        UUID createKey=UUID.randomUUID();Booking b=bookings.createBooking(createKey,request());
        assertEquals(b.getId(),bookings.createBooking(createKey,request()).getId());
        var hold=b.getHoldExpiresAt();UUID key=UUID.randomUUID();
        var first=quote(b.getId(),48000,key);
        assertEquals(BookingStatus.AWAITING_CUSTOMER_APPROVAL,first.getStatus());
        assertNull(first.getAgreedPrice());
        assertEquals(current(first).getId(),current(quote(b.getId(),48000,key)).getId());
        conflict(() -> quote(b.getId(),49000,key));
        var second=quote(b.getId(),49000,UUID.randomUUID());
        assertEquals(hold,second.getHoldExpiresAt());
        assertEquals(Quotation.StateEnum.SUPERSEDED,second.getQuotations().getFirst().getState());
        conflict(() -> providerBookings.acceptBooking(b.getId()));
        sign(other);assertEquals(404,assertThrows(ResponseStatusException.class,() -> negotiation.respond(b.getId(),UUID.randomUUID(),response(current(second)))).getStatusCode().value());
        sign(customer);conflict(() -> negotiation.respond(b.getId(),UUID.randomUUID(),response(current(first))));
        UUID acceptKey=UUID.randomUUID();var accepted=negotiation.respond(b.getId(),acceptKey,response(current(second)));
        assertEquals(BookingStatus.CONFIRMED,accepted.getStatus());assertEquals(49000L,accepted.getAgreedPrice().getAmount());
        assertEquals(45000L,accepted.getProposedPrice().getAmount());assertEquals(50000L,accepted.getListedPrice().getAmount());
        assertEquals(accepted.getId(),negotiation.respond(b.getId(),acceptKey,response(current(second))).getId());
        jdbc.update("UPDATE services SET name='Changed',price_amount=99000,active=false,deleted_at=now() WHERE id=?",service);
        assertEquals("Original Service",bookings.getBooking(b.getId()).getService().getName());
        sign(owner);assertEquals(b.getId(),bookings.getBooking(b.getId()).getId());
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM booking_transitions WHERE booking_id=?",Integer.class,b.getId())>=4);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id=? AND event_type='QUOTATION_ACCEPTED'",Integer.class,b.getId()));
    }
    @Test void acceptanceOfProposalAndRoleIsolation() {
        sign(owner);assertEquals(403,assertThrows(ResponseStatusException.class,() -> bookings.createBooking(UUID.randomUUID(),request())).getStatusCode().value());
        sign(customer);assertEquals(403,assertThrows(ResponseStatusException.class,onboarding::startOnboarding).getStatusCode().value());
        var b=create();sign(owner);var confirmed=providerBookings.acceptBooking(b.getId());
        assertEquals(45000L,confirmed.getAgreedPrice().getAmount());
        conflict(() -> providerBookings.completeBooking(b.getId()));
        sign(customer);assertEquals(403,assertThrows(ResponseStatusException.class,dashboard::getBusinessProfile).getStatusCode().value());
    }
    @Test void expiredHoldsReleaseSlotAndStaleQuoteCannotConfirm() {
        var b=create();var quoted=quote(b.getId(),48000,UUID.randomUUID());
        jdbc.update("UPDATE bookings SET response_deadline='2026-10-31T00:00:00Z' WHERE id=?",b.getId());
        sign(customer);conflict(() -> negotiation.respond(b.getId(),UUID.randomUUID(),response(current(quoted))));
        expiry.expire();assertEquals(BookingStatus.EXPIRED,bookings.getBooking(b.getId()).getStatus());
        assertEquals(Quotation.StateEnum.EXPIRED,current(bookings.getBooking(b.getId())).getState());
        assertEquals(BookingStatus.AWAITING_CONFIRMATION,create().getStatus());
    }
    @Test void quoteRejectionRequiresReasonAndReleasesSlot() {
        var b=create();var q=quote(b.getId(),48000,UUID.randomUUID());sign(customer);
        var input=response(current(q)).decision(QuotationResponse.DecisionEnum.REJECT);
        assertEquals(400,assertThrows(ResponseStatusException.class,() -> negotiation.respond(b.getId(),UUID.randomUUID(),input)).getStatusCode().value());
        var declined=negotiation.respond(b.getId(),UUID.randomUUID(),input.reason("Outside my budget"));
        assertEquals(BookingStatus.DECLINED,declined.getStatus());assertEquals("Outside my budget",declined.getDeclineReason());
        assertNotNull(create());
    }
    @Test void concurrentOverlappingRequestsHaveOnlyOneWinner() throws Exception {
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var gate=new CountDownLatch(1);
            var futures=new ArrayList<Future<Integer>>();
            for(UUID user:List.of(customer,other)) futures.add(pool.submit(() -> { gate.await();sign(user);
                try { bookings.createBooking(UUID.randomUUID(),request());return 201; }
                catch(ResponseStatusException e) { return e.getStatusCode().value(); }
                finally { SecurityContextHolder.clearContext(); }
            }));
            gate.countDown();var results=new ArrayList<Integer>();for(var f:futures) results.add(f.get(15,TimeUnit.SECONDS));
            Collections.sort(results);assertEquals(List.of(201,409),results);
        }
    }
    @Test void concurrentQuotationAcceptanceHasOnlyOneTransition() throws Exception {
        var b=create();var q=current(quote(b.getId(),48000,UUID.randomUUID()));
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var gate=new CountDownLatch(1);var futures=new ArrayList<Future<Integer>>();
            for(int i=0;i<2;i++) futures.add(pool.submit(() -> {gate.await();sign(customer);
                try { negotiation.respond(b.getId(),UUID.randomUUID(),response(q));return 200; }
                catch(ResponseStatusException e) { return e.getStatusCode().value(); }
                finally { SecurityContextHolder.clearContext(); }
            }));
            gate.countDown();var results=new ArrayList<Integer>();for(var f:futures) results.add(f.get(15,TimeUnit.SECONDS));
            Collections.sort(results);assertEquals(List.of(200,409),results);
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM booking_transitions WHERE booking_id=? AND to_status='CONFIRMED'",Integer.class,b.getId()));
    }
    @Test void checkoutReusesNegotiationAndListedPriceAsDefaultProposal() {
        var input=request();
        cart.addCartItem(new CartItemInput().providerId(business).serviceId(service).startsAt(input.getStartsAt()).fulfilment(input.getFulfilment()));
        UUID key=UUID.randomUUID();var result=cart.checkoutCart(key);
        assertEquals(1,result.size());assertEquals(BookingStatus.AWAITING_CONFIRMATION,result.getFirst().getStatus());
        assertEquals(50000L,result.getFirst().getProposedPrice().getAmount());assertNull(result.getFirst().getAgreedPrice());
        assertEquals(result.getFirst().getId(),cart.checkoutCart(key).getFirst().getId());
        assertTrue(cart.getCart().getItems().isEmpty());
    }
    @Test void dashboardSeparatesPendingQuotesAndReportsCompletedAgreedValue() {
        var b=create();var q=current(quote(b.getId(),48000,UUID.randomUUID()));
        var overview=dashboard.getDashboardOverview();
        assertEquals(1,overview.getAwaitingCustomerApproval());assertEquals(1,overview.getPendingHolds());
        assertEquals(0,overview.getToday());assertEquals(1,overview.getQuotationsAwaitingApproval().size());
        sign(customer);negotiation.respond(b.getId(),UUID.randomUUID(),response(q));
        jdbc.update("""
          UPDATE bookings SET starts_at='2026-10-31T10:00:00Z',ends_at='2026-10-31T11:00:00Z',
            hold_expires_at='2026-10-30T10:00:00Z',response_deadline='2026-10-30T10:00:00Z' WHERE id=?
          """,b.getId());
        sign(owner);providerBookings.completeBooking(b.getId());
        var value=dashboard.getEarnings("2026-10");
        assertEquals(48000L,value.getTotal().getAmount());assertEquals(48000L,value.getTotals().getFirst().getAmount());
        assertEquals(1,value.getCompletedBookings());assertEquals(0,dashboard.getDashboardOverview().getPendingHolds());
    }
    @Test void concurrentDuplicateRequestsReplayOneBooking() throws Exception {
        UUID key=UUID.randomUUID();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var gate=new CountDownLatch(1);var futures=new ArrayList<Future<UUID>>();
            for(int i=0;i<2;i++) futures.add(pool.submit(() -> {gate.await();sign(customer);
                try { return bookings.createBooking(key,request()).getId(); }
                finally { SecurityContextHolder.clearContext(); }
            }));
            gate.countDown();assertEquals(futures.getFirst().get(15,TimeUnit.SECONDS),futures.getLast().get(15,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM bookings WHERE business_id=?",Integer.class,business));
    }
    @Test void creationReleasesDueHoldsBeforeTheSweepAndOffsetRetriesAreEquivalent() {
        var b=create();jdbc.update("UPDATE bookings SET response_deadline='2026-10-31T00:00:00Z' WHERE id=?",b.getId());
        UUID key=UUID.randomUUID();var request=request();var next=bookings.createBooking(key,request);
        request.startsAt(request.getStartsAt().withOffsetSameInstant(ZoneOffset.UTC));
        assertEquals(next.getId(),bookings.createBooking(key,request).getId());
        assertEquals(BookingStatus.EXPIRED,bookings.getBooking(b.getId()).getStatus());
        request.proposedPrice(new Money(44000L,"INR"));conflict(() -> bookings.createBooking(key,request));
    }
    @Test void readyReelMetadataAndOwnershipAreEnforced() {
        UUID upload=UUID.randomUUID();jdbc.update("""
           INSERT INTO uploads(id,user_id,purpose,object_key,content_type,size_bytes,status,processing_state,width,height,duration_seconds)
           VALUES(?,?,'GALLERY_VIDEO',?,'video/mp4',10,'UPLOADED','READY',1080,1920,30)
           """,upload,owner,upload.toString());
        sign(owner);var item=dashboard.addOwnMedia(new AddOwnMediaRequest().uploadId(upload).presentationFormat(AddOwnMediaRequest.PresentationFormatEnum.REEL).publicationState(AddOwnMediaRequest.PublicationStateEnum.DRAFT));
        assertNull(item.getUrl());assertEquals(1080,item.getWidth());
        assertEquals(0,profiles.listProviderMedia(business,null,0,20).getContent().size());
        dashboard.updateOwnMedia(item.getId(),new MediaUpdate().publicationState(MediaUpdate.PublicationStateEnum.PUBLISHED));
        sign(customer);assertEquals(MediaItem.PresentationFormatEnum.REEL,profiles.listProviderMedia(business,null,0,20).getContent().getFirst().getPresentationFormat());
        UUID otherOwner=UUID.randomUUID(),otherBusiness=UUID.randomUUID();new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.update("INSERT INTO users(id,email) VALUES(?,?)",otherOwner,otherOwner+"@test.example");
            jdbc.update("INSERT INTO user_roles(user_id,role) VALUES(?, 'BUSINESS')",otherOwner);
            jdbc.update("INSERT INTO businesses(id,owner_user_id) VALUES(?,?)",otherBusiness,otherOwner);
        });
        sign(otherOwner);assertEquals(404,assertThrows(ResponseStatusException.class,() -> dashboard.updateOwnMedia(item.getId(),new MediaUpdate().caption("Not mine"))).getStatusCode().value());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,() -> jdbc.update("INSERT INTO business_media(business_id,upload_id,type,url) VALUES(?,?,'VIDEO','https://test/video')",otherBusiness,upload));
    }
    @Test void failedMediaCannotPublishAndCrossOwnerUploadCannotAttach() {
        UUID upload=UUID.randomUUID();jdbc.update("""
           INSERT INTO uploads(id,user_id,purpose,object_key,content_type,size_bytes,status,processing_state)
           VALUES(?,?,'GALLERY_VIDEO',?,'video/mp4',10,'UPLOADED','FAILED')
           """,upload,owner,upload.toString());
        UUID media=jdbc.queryForObject("INSERT INTO business_media(business_id,upload_id,type,url) VALUES(?,?,'VIDEO','https://test/video') RETURNING id",UUID.class,business,upload);
        assertEquals("DRAFT",jdbc.queryForObject("SELECT publication_state FROM business_media WHERE id=?",String.class,media));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,() -> jdbc.update("UPDATE business_media SET publication_state='PUBLISHED' WHERE id=?",media));
        sign(customer);assertEquals(0,profiles.listProviderMedia(business,null,0,20).getContent().size());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,() -> jdbc.update("UPDATE uploads SET user_id=? WHERE id=?",other,upload));
    }
}
