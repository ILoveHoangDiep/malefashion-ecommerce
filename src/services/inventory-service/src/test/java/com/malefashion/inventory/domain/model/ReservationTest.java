package com.malefashion.inventory.domain.model;

import com.malefashion.inventory.domain.exception.InvalidQuantityException;
import com.malefashion.inventory.domain.exception.InvalidReservationStateException;
import com.malefashion.inventory.domain.exception.ReservationExpiredException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationTest {

    private static final UUID ORDER_ID = UUID.fromString("5d1c9b2a-8e4f-4a6b-b1c2-3d4e5f6a7b8c");
    private static final UUID VARIANT_ID = UUID.fromString("52e1d8ae-7c8f-5ec8-9ce7-a4c81caed6f5");
    private static final Instant NOW = Instant.parse("2026-10-22T03:15:30.123Z");
    private static final Instant EXPIRES_AT = NOW.plus(Duration.ofMinutes(15));

    @Test
    void reserveCreatesReservedReservationWithItems() {
        Reservation reservation = newReservation();

        assertThat(reservation.getId()).isNotNull();
        assertThat(reservation.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(reservation.getCreatedAt()).isEqualTo(NOW);
        assertThat(reservation.getUpdatedAt()).isEqualTo(NOW);
        assertThat(reservation.getExpiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(reservation.getItems()).hasSize(1);
        assertThat(reservation.getItems().getFirst().getVariantId()).isEqualTo(VARIANT_ID);
        assertThat(reservation.getItems().getFirst().getSku()).isEqualTo("MF-TEE-BLACK-M");
        assertThat(reservation.getItems().getFirst().getQuantity()).isEqualTo(2);
    }

    @Test
    void commitMovesReservedReservationToCommitted() {
        Reservation reservation = newReservation();

        reservation.commit(NOW.plusSeconds(30));

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.COMMITTED);
        assertThat(reservation.getUpdatedAt()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void commitFailsWhenExpiredAtTheExactExpiryInstant() {
        Reservation reservation = newReservation();

        assertThatThrownBy(() -> reservation.commit(EXPIRES_AT))
                .isInstanceOf(ReservationExpiredException.class);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    void commitFailsAfterExpiryAndKeepsReservationReservedForTheExpiryJob() {
        Reservation reservation = newReservation();

        assertThatThrownBy(() -> reservation.commit(EXPIRES_AT.plusSeconds(1)))
                .isInstanceOf(ReservationExpiredException.class);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    void releaseMovesReservedReservationToReleased() {
        Reservation reservation = newReservation();

        reservation.release(NOW.plusSeconds(30));

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    }

    @Test
    void expireMovesReservedReservationToExpired() {
        Reservation reservation = newReservation();

        reservation.expire(EXPIRES_AT.plusSeconds(1));

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    void commitAfterReleaseIsRejected() {
        Reservation reservation = newReservation();
        reservation.release(NOW.plusSeconds(30));

        assertThatThrownBy(() -> reservation.commit(NOW.plusSeconds(31)))
                .isInstanceOf(InvalidReservationStateException.class)
                .satisfies(exception -> {
                    InvalidReservationStateException stateException =
                            (InvalidReservationStateException) exception;
                    assertThat(stateException.getCurrentStatus()).isEqualTo(ReservationStatus.RELEASED);
                    assertThat(stateException.getAction()).isEqualTo("commit");
                });
    }

    @Test
    void releaseAfterCommitIsRejected() {
        Reservation reservation = newReservation();
        reservation.commit(NOW.plusSeconds(30));

        assertThatThrownBy(() -> reservation.release(NOW.plusSeconds(31)))
                .isInstanceOf(InvalidReservationStateException.class);
    }

    @Test
    void expireTwiceIsRejected() {
        Reservation reservation = newReservation();
        reservation.expire(EXPIRES_AT.plusSeconds(1));

        assertThatThrownBy(() -> reservation.expire(EXPIRES_AT.plusSeconds(2)))
                .isInstanceOf(InvalidReservationStateException.class);
    }

    @Test
    void releaseAfterExpiryIsRejected() {
        Reservation reservation = newReservation();
        reservation.expire(EXPIRES_AT.plusSeconds(1));

        assertThatThrownBy(() -> reservation.release(EXPIRES_AT.plusSeconds(2)))
                .isInstanceOf(InvalidReservationStateException.class);
    }

    @Test
    void reserveRejectsEmptyItemList() {
        assertThatThrownBy(() -> Reservation.reserve(ORDER_ID, List.of(), NOW, EXPIRES_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("items");
    }

    @Test
    void reserveRejectsExpiryThatIsNotAfterCreation() {
        ReservationItem item = ReservationItem.create(VARIANT_ID, "MF-TEE-BLACK-M", 1);

        assertThatThrownBy(() -> Reservation.reserve(ORDER_ID, List.of(item), NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expiresAt");
    }

    @Test
    void reservationItemRejectsNonPositiveQuantity() {
        assertThatThrownBy(() -> ReservationItem.create(VARIANT_ID, "MF-TEE-BLACK-M", 0))
                .isInstanceOf(InvalidQuantityException.class);
    }

    @Test
    void reservationItemRejectsBlankSku() {
        assertThatThrownBy(() -> ReservationItem.create(VARIANT_ID, " ", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Reservation newReservation() {
        ReservationItem item = ReservationItem.create(VARIANT_ID, "MF-TEE-BLACK-M", 2);
        return Reservation.reserve(ORDER_ID, List.of(item), NOW, EXPIRES_AT);
    }
}
