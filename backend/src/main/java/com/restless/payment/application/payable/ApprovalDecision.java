package com.restless.payment.application.payable;

/**
 * Handler가 승인 시작 또는 승인 결과를 받아들이는지에 대한 업무 판단. 기술 오류는 이 값이 아니라 예외로 표현한다.
 *
 * @param reason reject일 때 사유 (로그와 결제 실패 사유에 남는다). accept면 null
 */
public record ApprovalDecision(boolean accepted, String reason) {
    private static final ApprovalDecision ACCEPT = new ApprovalDecision(true, null);

    public ApprovalDecision {
        if (accepted && reason != null) throw new IllegalArgumentException("accept has no reason");
        if (!accepted && (reason == null || reason.isBlank())) throw new IllegalArgumentException("reject requires a reason");
    }

    public static ApprovalDecision accept() {
        return ACCEPT;
    }

    public static ApprovalDecision reject(String reason) {
        return new ApprovalDecision(false, reason);
    }

    public boolean rejected() {
        return !accepted;
    }
}
