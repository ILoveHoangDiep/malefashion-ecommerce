package com.malefashion.inventory.domain.exception;

import com.malefashion.inventory.domain.model.ReservationStatus;

import java.util.UUID;

public class InvalidReservationStateException extends DomainException {

    private final UUID reservationId;
    private final ReservationStatus currentStatus;
    private final String action;

    public InvalidReservationStateException(UUID reservationId, ReservationStatus currentStatus, String action) {
        super("Cannot " + action + " reservation " + reservationId + " while it is " + currentStatus);
        this.reservationId = reservationId;
        this.currentStatus = currentStatus;
        this.action = action;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public ReservationStatus getCurrentStatus() {
        return currentStatus;
    }

    public String getAction() {
        return action;
    }
}
