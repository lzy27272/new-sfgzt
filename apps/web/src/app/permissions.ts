export const permissions = Object.freeze({
  dailyReport: {
    readOwn: 'daily-report.read',
    editOwn: 'daily-report.submit',
    submit: 'daily-report.submit',
    readTeam: 'daily-report.team-read',
    reviewException: 'daily-report.review',
    reviewCorrection: 'daily-report.revision-review',
  },
  dailyReportTemplate: {
    read: 'daily-report-template.read',
    create: 'daily-report-template.manage',
    edit: 'daily-report-template.manage',
    review: 'daily-report-template.review',
    publish: 'daily-report-template.publish',
    retire: 'daily-report-template.publish',
    supplement: 'daily-report-template.store-supplement',
  },
  dailyOperations: {
    readHotel: 'daily-operation.read',
    readCrossHotel: 'daily-operation.cross-hotel-read',
    confirmIssue: 'issue.confirm',
    assignIssue: 'issue.assign',
    closeIssue: 'issue.close',
    reopenIssue: 'issue.reopen',
    retrySnapshot: 'operation-snapshot.retry',
  },
  taskCandidate: {
    read: 'task-candidate.read',
    edit: 'task-candidate.manage',
    confirm: 'task-candidate.confirm',
    reject: 'task-candidate.reject',
    retry: 'task-candidate.retry',
  },
  evidence: {
    read: 'daily-report.read',
    readSensitive: 'evidence.sensitive.read',
    upload: 'daily-report.submit',
    invalidate: 'daily-report.submit',
  },
  snapshot: {
    read: 'operation-snapshot.read',
    compare: 'operation-snapshot.compare',
  },
  export: {
    create: 'operation-export.create',
    download: 'operation-export.download',
    sensitive: 'operation-export.sensitive',
  },
  ai: {
    read: 'ai-recommendation.read',
    feedback: 'ai-recommendation.feedback',
    adopt: 'ai-recommendation.adopt',
  },
  kpi: {
    metricRead: 'kpi.metric.read',
    metricManage: 'kpi.metric.manage',
    templateRead: 'kpi.template.read',
    templateManage: 'kpi.template.manage',
    templateReview: 'kpi.template.review',
    templatePublish: 'kpi.template.publish',
    templateImport: 'kpi.template.import',
    relationManage: 'kpi.relation.manage',
    scorecardReadOwn: 'kpi.scorecard.read-own',
    scorecardReadTeam: 'kpi.scorecard.read-team',
    scorecardReadAll: 'kpi.scorecard.read-all',
    scorecardGenerate: 'kpi.scorecard.generate',
    scorecardManual: 'kpi.scorecard.manual-score',
    scorecardReview: 'kpi.scorecard.review',
    scorecardDispute: 'kpi.scorecard.dispute',
    scorecardLock: 'kpi.scorecard.lock',
    settlementRead: 'kpi.settlement.read',
    settlementManage: 'kpi.settlement.manage',
    inspectionSubmit: 'kpi.inspection.submit',
    inspectionReadTeam: 'kpi.inspection.read-team',
    inspectionVerify: 'kpi.inspection.verify',
    inspectionManage: 'kpi.inspection.manage',
    export: 'kpi.export',
  },
  investment: {
    read: 'investment.read',
    manage: 'investment.manage',
    confirm: 'investment.confirm',
    configure: 'investment.configure',
    parameterConfirm: 'investment.parameter-confirm',
    export: 'investment.export',
    audit: 'investment.audit',
  },
  wecomBinding: {
    read: 'wecom-binding.read',
    manage: 'wecom-binding.manage',
    approve: 'wecom-binding.approve',
    onboardingReview: 'wecom-onboarding.review',
  },
  workPlan: {
    read: 'work-plan.read',
    submit: 'work-plan.submit',
    readTeam: 'work-plan.team-read',
    review: 'work-plan.review',
  },
  workRecord: {
    readTeam: 'work-record.team-read',
  },
  executiveTask: {
    read: 'executive-task.read',
    assign: 'executive-task.assign',
  },
  storeResource: {
    read: 'store-resource.read',
    itemManage: 'store-resource.item-manage',
    movementCreate: 'store-resource.movement-create',
    costClose: 'store-resource.cost-close',
    assetManage: 'store-resource.asset-manage',
    importPreview: 'store-resource.import-preview',
    importApply: 'store-resource.import-apply',
    handoverCreate: 'store-resource.handover-create',
    handoverConfirm: 'store-resource.handover-confirm',
    handoverApprove: 'store-resource.handover-approve',
  },
})

export function hasPermission(granted: string[], permission: string): boolean {
  return granted.includes('*') || granted.includes(permission)
}

export function hasAnyPermission(granted: string[], required: readonly string[]): boolean {
  return required.length === 0 || granted.includes('*') || required.some((permission) => granted.includes(permission))
}

export function hasAllPermissions(granted: string[], required: readonly string[]): boolean {
  return required.length === 0 || granted.includes('*') || required.every((permission) => granted.includes(permission))
}
