package com.limitedgoodsreservation.waitingroom.scheduler;

import com.limitedgoodsreservation.waitingroom.service.WaitingRoomService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WaitingRoomAdmissionScheduler {

    private final WaitingRoomService waitingRoomService;

    public WaitingRoomAdmissionScheduler(WaitingRoomService waitingRoomService) {
        this.waitingRoomService = waitingRoomService;
    }

    @Scheduled(fixedDelayString = "${waiting-room.admission.interval-ms:1000}")
    public void admit() {
        if (!waitingRoomService.isAdmissionSchedulerEnabled()) {
            return;
        }
        waitingRoomService.admitDefaultProduct();
    }
}
