package com.limitedgoods;
import com.limitedgoods.config.ApiError;
import com.limitedgoods.purchases.*;
import com.limitedgoods.sales.*;
import com.limitedgoods.reservations.*;
import com.limitedgoods.payments.*;
import com.limitedgoods.mockpg.MockPaymentStore;
import com.limitedgoods.worker.PaymentJobs;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:postgresql://localhost:5433/limited_goods_test",
    "app.admission.enabled=false","app.worker.enabled=false","app.mock.enabled=true"
})
@Import(ContractTest.TimeConfig.class)
class ContractTest {
    static class MutableClock extends Clock {
        volatile Instant now=Instant.parse("2026-01-01T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
        void advance(long seconds) { now=now.plusSeconds(seconds); }
    }
    @TestConfiguration static class TimeConfig { @Bean @Primary MutableClock testClock() { return new MutableClock(); } }
    @Autowired MutableClock clock;
    @Autowired SaleService sales;
    @Autowired PurchaseService purchases;
    @Autowired OrderQueryService orders;
    @Autowired PaymentService payments;
    @Autowired ReservationService reservations;
    @Autowired PaymentJobs jobs;
    @Autowired MockPaymentStore pg;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;
    @BeforeEach void reset() {
        assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("limited_goods_test");
        jdbc.execute("truncate mock_pg_receipts,payment_attempts,reservations,order_items,orders,sale_items,sales");
        clock.now=Instant.parse("2026-01-01T00:00:00Z");
    }
    @AfterEach void invariants() {
        assertThat(jdbc.queryForObject("""
            select count(*) from sale_items s where total<>available+held+sold
            or held<>coalesce((select sum(i.quantity) from order_items i join orders o on o.id=i.order_id
                             where i.sale_item_id=s.id and o.status in ('PAYMENT_PENDING','PAYMENT_PROCESSING')),0)
            or sold<>coalesce((select sum(i.quantity) from order_items i join orders o on o.id=i.order_id
                             where i.sale_item_id=s.id and o.status='CONFIRMED'),0)
            """,Long.class)).isZero();
    }
    SaleService.View sale(int stock,int limit,int itemCount) {
        return sales.create(new SaleService.Create("goods",clock.instant().minusSeconds(1),
            java.util.stream.IntStream.range(0,itemCount).mapToObj(i->new SaleService.ItemInput("item-"+i,10000,stock,limit)).toList()));
    }
    PurchaseRequest request(SaleService.View sale,int quantity) {
        return new PurchaseRequest(sale.id(),sale.items().stream().map(i->new PurchaseRequest.Item(i.id(),quantity)).toList());
    }
    OrderView buy(SaleService.View sale,String user,String key,int quantity) { return purchases.purchase(user,key,request(sale,quantity)); }
    <T> List<T> concurrent(int n,IntFunction<T> action) throws Exception {
        var ready=new CyclicBarrier(n);
        try(var pool=Executors.newFixedThreadPool(n)) {
            List<Future<T>> futures=new ArrayList<>();
            for(int i=0;i<n;i++) { final int id=i; futures.add(pool.submit(()->{ready.await(5,TimeUnit.SECONDS); return action.apply(id);})); }
            List<T> results=new ArrayList<>();
            for(var future:futures) results.add(future.get(15,TimeUnit.SECONDS));
            return results;
        }
    }
    String attempt(java.util.function.Supplier<OrderView> supplier) {
        try { supplier.get(); return "OK"; } catch(ApiError e) { return e.code; }
    }
    @Test void preopenSaleCanBeRead() {
        var sale=sales.create(new SaleService.Create("future",clock.instant().plusSeconds(10),
            List.of(new SaleService.ItemInput("goods",100,1,1))));
        assertThat(sales.get(sale.id()).items()).hasSize(1);
        assertThatThrownBy(()->buy(sale,"u","key",1)).isInstanceOf(ApiError.class).hasMessage("SALE_NOT_OPEN");
    }
    @Test void multiItemRejectionRollsBackAllRows() {
        var sale=sale(2,2,2);
        purchases.purchase("first","key",new PurchaseRequest(sale.id(),List.of(new PurchaseRequest.Item(sale.items().get(1).id(),2))));
        assertThatThrownBy(()->buy(sale,"second","key",1)).hasMessage("TEMPORARILY_UNAVAILABLE");
        assertThat(jdbc.queryForObject("select count(*) from orders",Long.class)).isEqualTo(1);
        assertThat(sales.get(sale.id()).items().get(0).available()).isEqualTo(2);
    }
    @Test void lastUnitCannotOversell() throws Exception {
        var sale=sale(1,1,1);
        var results=concurrent(6,i->attempt(()->buy(sale,"u"+i,"k",1)));
        assertThat(results.stream().filter("OK"::equals).count()).isEqualTo(1);
        assertThat(sales.get(sale.id()).items().getFirst().held()).isEqualTo(1);
    }
    @Test void sameUserCannotBypassLimit() throws Exception {
        var sale=sale(20,2,1);
        var results=concurrent(6,i->attempt(()->buy(sale,"same","key"+i,2)));
        assertThat(results.stream().filter("OK"::equals).count()).isEqualTo(1);
        assertThat(sales.get(sale.id()).items().getFirst().held()).isEqualTo(2);
    }
    @Test void concurrentIdempotencyHasOneCommittedOrder() throws Exception {
        var sale=sale(1,1,1);
        var ids=concurrent(6,i->buy(sale,"same","same",1).id());
        assertThat(new HashSet<>(ids)).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from orders",Long.class)).isEqualTo(1);
        assertThatThrownBy(()->buy(sale,"same","same",2)).hasMessage("IDEMPOTENCY_KEY_REUSED");
    }
    @Test void reversedItemsUseSameLockOrder() throws Exception {
        var sale=sale(1,1,2);
        var items=new ArrayList<>(request(sale,1).items()); Collections.reverse(items);
        var reversed=new PurchaseRequest(sale.id(),items);
        var results=concurrent(2,i->attempt(()->purchases.purchase("u"+i,"k",i==0?request(sale,1):reversed)));
        assertThat(results.stream().filter("OK"::equals).count()).isEqualTo(1);
    }
    @Test void expiryReturnsStockAndUserAllowanceExactlyOnce() {
        var sale=sale(2,2,1); var order=buy(sale,"u","first",2);
        clock.advance(61);
        assertThat(reservations.expire(order.id())).isTrue();
        assertThat(reservations.expire(order.id())).isFalse();
        assertThat(buy(sale,"u","next",2).status()).isEqualTo("PAYMENT_PENDING");
    }
    @Test void duplicateSuccessAndParallelExpiryCannotDoubleChangeStock() throws Exception {
        var sale=sale(2,2,1); var order=buy(sale,"u","first",2);
        var payment=payments.start(order.id(),"u","pay",PaymentService.Scenario.SUCCESS);
        clock.advance(80);
        concurrent(4,i->{if(i%2==0) payments.apply(payment.id(),20000,PaymentService.Result.SUCCEEDED);
                         else reservations.expire(order.id()); return true;});
        assertThat(orders.get(order.id(),"u").status()).isEqualTo("CONFIRMED");
        assertThat(sales.get(sale.id()).items().getFirst().sold()).isEqualTo(2);
    }
    @Test void failedAttemptAllowsRetryWithoutExtendingHold() {
        var sale=sale(2,2,1); var order=buy(sale,"u","first",2);
        var p=payments.start(order.id(),"u","p1",PaymentService.Scenario.FAILURE);
        payments.apply(p.id(),20000,PaymentService.Result.FAILED);
        clock.advance(10);
        payments.start(order.id(),"u","p2",PaymentService.Scenario.SUCCESS);
        assertThat(orders.get(order.id(),"u").holdExpiresAt()).isEqualTo(order.holdExpiresAt());
    }
    @Test void unknownBlocksRetryAndExpiry() {
        var sale=sale(1,1,1); var order=buy(sale,"u","first",1);
        var p=payments.start(order.id(),"u","p",PaymentService.Scenario.UNKNOWN);
        payments.apply(p.id(),10000,PaymentService.Result.UNKNOWN);
        clock.advance(80);
        assertThat(reservations.expire(order.id())).isFalse();
        assertThatThrownBy(()->payments.start(order.id(),"u","again",PaymentService.Scenario.SUCCESS))
            .hasMessage("PAYMENT_ATTEMPT_BLOCKED");
        assertThat(sales.get(sale.id()).items().getFirst().held()).isEqualTo(1);
    }
    @Test void paymentOwnershipIsCheckedBeforeIdempotentReplay() {
        var order=buy(sale(1,1,1),"owner","first",1);
        payments.start(order.id(),"owner","pay",PaymentService.Scenario.SUCCESS);
        assertThatThrownBy(()->payments.start(order.id(),"other","pay",PaymentService.Scenario.SUCCESS)).hasMessage("ORDER_NOT_FOUND");
        assertThatThrownBy(()->orders.get(order.id(),"other")).hasMessage("ORDER_NOT_FOUND");
    }
    @Test void concurrentPaymentAcceptanceHasOneAttempt() throws Exception {
        var order=buy(sale(1,1,1),"u","first",1);
        var ids=concurrent(6,i->payments.start(order.id(),"u","same",PaymentService.Scenario.SUCCESS).id());
        assertThat(new HashSet<>(ids)).hasSize(1);
    }
    @Test void failedLatePaymentReleasesStock() {
        var sale=sale(1,1,1); var order=buy(sale,"u","first",1);
        var p=payments.start(order.id(),"u","pay",PaymentService.Scenario.FAILURE);
        clock.advance(61);
        payments.apply(p.id(),10000,PaymentService.Result.FAILED);
        assertThat(orders.get(order.id(),"u").status()).isEqualTo("EXPIRED");
        assertThat(sales.get(sale.id()).items().getFirst().available()).isEqualTo(1);
    }
    @Test void abandonedWorkerLeaseRecoversWithStableProviderKey() {
        var order=buy(sale(1,1,1),"u","first",1);
        var p=payments.start(order.id(),"u","pay",PaymentService.Scenario.LOST_RESPONSE);
        assertThat(jobs.claim()).isEqualTo(p.id());
        assertThat(jobs.claim()).isNull();
        var work=payments.work(p.id());
        assertThat(pg.accept(work).loseResponse()).isTrue(); // PG committed; application has no result.
        clock.advance(11);
        assertThat(jobs.claim()).isEqualTo(p.id());
        var replay=pg.accept(work);
        assertThat(replay.loseResponse()).isFalse();
        payments.apply(p.id(),work.amount(),replay.response().result());
        assertThat(orders.get(order.id(),"u").status()).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("select count(*) from mock_pg_receipts",Long.class)).isEqualTo(1);
    }
    @Test void providerRejectsFirstSubmissionAfterDeadline() {
        var order=buy(sale(1,1,1),"u","first",1);
        var p=payments.start(order.id(),"u","pay",PaymentService.Scenario.SUCCESS);
        clock.advance(71);
        var work=payments.work(p.id()); var receipt=pg.accept(work);
        assertThat(receipt.response().result()).isEqualTo(PaymentService.Result.FAILED);
        payments.apply(p.id(),work.amount(),receipt.response().result());
        assertThat(orders.get(order.id(),"u").status()).isEqualTo("EXPIRED");
    }
    @Test void delayedProviderResultIsReconciled() {
        var order=buy(sale(1,1,1),"u","first",1);
        var p=payments.start(order.id(),"u","pay",PaymentService.Scenario.DELAYED_SUCCESS);
        var work=payments.work(p.id());
        payments.apply(p.id(),work.amount(),pg.accept(work).response().result());
        assertThat(orders.get(order.id(),"u").payments().getFirst().status()).isEqualTo("UNKNOWN");
        clock.advance(4);
        payments.apply(p.id(),work.amount(),pg.accept(work).response().result());
        assertThat(orders.get(order.id(),"u").status()).isEqualTo("CONFIRMED");
    }
    @Test void callbackChecksSecretAndAmount() {
        var order=buy(sale(1,1,1),"u","first",1);
        var p=payments.start(order.id(),"u","pay",PaymentService.Scenario.SUCCESS);
        var headers=new HttpHeaders(); headers.set("X-PG-Secret","wrong");
        var body=new PaymentController.Callback(p.id(),10000,PaymentService.Result.SUCCEEDED);
        assertThat(http.postForEntity("/api/payments/callback",new HttpEntity<>(body,headers),String.class).getStatusCode().value()).isEqualTo(403);
        headers.set("X-PG-Secret","local-pg-secret");
        var invalid=new PaymentController.Callback(p.id(),1,PaymentService.Result.SUCCEEDED);
        assertThat(http.postForEntity("/api/payments/callback",new HttpEntity<>(invalid,headers),String.class).getStatusCode().value()).isEqualTo(409);
        assertThat(http.postForEntity("/api/payments/callback",new HttpEntity<>(body,headers),String.class).getStatusCode().value()).isEqualTo(204);
    }
    @Test void httpValidationRejectsInvalidQuantityBeforeDatabaseMutation() {
        var sale=sale(1,1,1);
        var headers=new HttpHeaders(); headers.set("X-User-Id","u"); headers.set("Idempotency-Key","key");
        var response=http.postForEntity("/api/purchases",new HttpEntity<>(request(sale,0),headers),String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        var nullItem=new PurchaseRequest(sale.id(),Arrays.asList((PurchaseRequest.Item)null));
        assertThat(http.postForEntity("/api/purchases",new HttpEntity<>(nullItem,headers),String.class)
            .getStatusCode().value()).isEqualTo(400);
        assertThat(jdbc.queryForObject("select count(*) from orders",Long.class)).isZero();
    }
}
