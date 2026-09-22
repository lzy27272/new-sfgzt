package cn.sifangguan.hotelaios.finance;

import java.util.List;

/** Only initial self-review may be skipped. Missing reviewers and later self-review fail closed. */
final class FinanceWorkflow {
    private FinanceWorkflow() { }
    static List<String> stages(int version) {
        return version>=2 ? List.of("MANAGER_REVIEW","FINANCE_REVIEW","DIRECTOR_REVIEW","LEADER_REVIEW")
            : List.of("MANAGER_REVIEW","FINANCE_REVIEW","LEADER_REVIEW");
    }
    static String accountColumn(String stage) {
        return switch(stage) {
            case "MANAGER_REVIEW" -> "manager_account_id";
            case "FINANCE_REVIEW" -> "finance_account_id";
            case "DIRECTOR_REVIEW" -> "director_account_id";
            case "LEADER_REVIEW" -> "leader_account_id";
            case "PAYMENT" -> "cashier_account_id";
            default -> throw new IllegalArgumentException("未知审批节点");
        };
    }
    static String label(String stage,int version) {
        return switch(stage) {
            case "MANAGER_REVIEW" -> version>=2 ? "初审" : "门店负责人";
            case "FINANCE_REVIEW" -> version>=2 ? "门店财务" : "财务";
            case "DIRECTOR_REVIEW" -> "财务总监";
            case "LEADER_REVIEW" -> version>=2 ? "CEO终审" : "集团领导";
            case "PAYMENT" -> "出纳登记付款";
            default -> throw new IllegalArgumentException("未知审批节点");
        };
    }
    static String next(String stage,int version) {
        var stages=stages(version);int index=stages.indexOf(stage);
        if(index<0) throw new IllegalArgumentException("当前不是有效审核节点");
        return index==stages.size()-1 ? "APPROVED" : stages.get(index+1);
    }
}
