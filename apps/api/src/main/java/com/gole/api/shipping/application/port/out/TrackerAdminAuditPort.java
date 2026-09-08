package com.gole.api.shipping.application.port.out;

/** No credentials, provider response or raw waybill may cross this boundary. */
public interface TrackerAdminAuditPort {
    void record(String actorId, String action, String targetId);
}
