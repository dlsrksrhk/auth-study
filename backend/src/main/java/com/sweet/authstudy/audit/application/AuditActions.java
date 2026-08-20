package com.sweet.authstudy.audit.application;

public final class AuditActions {
    public static final String COMPANY_CREATE = "COMPANY_CREATE";
    public static final String COMPANY_UPDATE = "COMPANY_UPDATE";
    public static final String COMPANY_STATUS_CHANGE = "COMPANY_STATUS_CHANGE";
    public static final String POSITION_CREATE = "POSITION_CREATE";
    public static final String POSITION_UPDATE = "POSITION_UPDATE";
    public static final String POSITION_STATUS_CHANGE = "POSITION_STATUS_CHANGE";
    public static final String DEPARTMENT_CREATE = "DEPARTMENT_CREATE";
    public static final String DEPARTMENT_UPDATE = "DEPARTMENT_UPDATE";
    public static final String DEPARTMENT_MOVE = "DEPARTMENT_MOVE";
    public static final String DEPARTMENT_STATUS_CHANGE = "DEPARTMENT_STATUS_CHANGE";
    public static final String USER_CREATE = "USER_CREATE";
    public static final String USER_UPDATE = "USER_UPDATE";
    public static final String USER_STATUS_CHANGE = "USER_STATUS_CHANGE";
    public static final String USER_TEMPORARY_PASSWORD_RESET = "USER_TEMPORARY_PASSWORD_RESET";
    public static final String MEMBERSHIP_CREATE = "MEMBERSHIP_CREATE";
    public static final String MEMBERSHIP_UPDATE = "MEMBERSHIP_UPDATE";
    public static final String MEMBERSHIP_END = "MEMBERSHIP_END";
    public static final String COMPANY_ADMIN_GRANT = "COMPANY_ADMIN_GRANT";
    public static final String COMPANY_ADMIN_REVOKE = "COMPANY_ADMIN_REVOKE";

    private AuditActions() {}
}
