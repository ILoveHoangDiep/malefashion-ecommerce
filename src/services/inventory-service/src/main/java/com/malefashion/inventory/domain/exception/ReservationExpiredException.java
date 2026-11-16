package com.malefashion.inventory.domain.exception;

import java.util.UUID;

public class ReservationExpiredException extends DomainException {

    private final UUID reservationId;

    public ReservationExpiredException(UUID reservationId) {
        super("Reservation " + reservationId + " has expired");
        this.reservationId = reservationId;
    }

    public UUID getReservationId() {
        return reservationId;
    }
}
