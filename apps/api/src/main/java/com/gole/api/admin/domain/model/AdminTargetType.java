package com.gole.api.admin.domain.model;

/**
 * 관리자 조치의 대상 종류. (admin-console 요구사항 8.1)
 */
public enum AdminTargetType {
    LISTING,
    POST,
    COMMENT,
    ACCOUNT,
    ACCOUNT_DELETION_REQUEST,
    REPORT,
    REVIEW,
    CATALOG_SET,
    ORDER,
    SETTLEMENT,
    SUPPORT_TICKET,
    SUPPORT_NOTIFICATION,
    CHAT_REPORT_SNAPSHOT,
    LAUNCH_CONFIG,
    PROMOTION_POST
}
