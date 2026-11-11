package com.restless.payment.application.pg;

public interface PgClient {
    PgApprovalResult approve(PgApproveCommand command);
    PgQueryResult query(String pgOrderId);
    PgCancelResult cancel(PgCancelCommand command);
}
