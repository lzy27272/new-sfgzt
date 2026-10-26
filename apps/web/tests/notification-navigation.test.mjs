import assert from 'node:assert/strict'
import test from 'node:test'
import { resolveNotificationNavigation } from '../src/app/notificationNavigation.ts'

test('V2 typed pending links retain independent ledger and tax permissions', () => {
  const sourceId = '10000000-0000-0000-0000-000000000001'
  const item = sourceType => ({ type: 'FINANCE_REVIEW_PENDING', sourceType, sourceId })
  assert.equal(resolveNotificationNavigation(item('FINANCE_INPUT_BASIS'), ['finance.review']), undefined)
  assert.deepEqual(resolveNotificationNavigation(item('FINANCE_INPUT_BASIS'), ['finance.review', 'finance.tax'])?.params, { tab: 'tax-control', section: 'inputs', basisId: sourceId })
  assert.equal(resolveNotificationNavigation(item('FINANCE_ASSET_GAIN'), ['finance.review']), undefined)
  assert.deepEqual(resolveNotificationNavigation(item('FINANCE_LOAN_OVERDUE'), ['finance.review'])?.params, { tab: 'operations', screen: 'loans', documentId: sourceId })
})

test('企业微信待确认通知直达对应绑定申请', () => {
  assert.deepEqual(resolveNotificationNavigation({
    type: 'WECOM_BINDING_PENDING', sourceType: 'WECOM_BINDING_REQUEST', sourceId: 'request-1',
  }, ['wecom-binding.approve']), {
    view: 'wecom-bindings', params: { requestId: 'request-1' }, actionLabel: '去确认',
  })
  assert.equal(resolveNotificationNavigation({
    type: 'WECOM_BINDING_PENDING', sourceType: 'WECOM_BINDING_REQUEST', sourceId: 'request-1',
  }, ['notification.read']), undefined)
  assert.deepEqual(resolveNotificationNavigation({
    type: 'WECOM_BINDING_ACTIVE', sourceType: 'WECOM_BINDING_REQUEST', sourceId: 'request-1',
  }, ['notification.read']), {
    view: 'workbench', actionLabel: '查看我的状态',
  })
})

test('企业微信自动入职与通讯录死信通知直达中台处理页', () => {
  assert.deepEqual(resolveNotificationNavigation({
    type: 'WECOM_ONBOARDING_PENDING_APPROVAL', sourceType: 'WECOM_PERSON_ONBOARDING', sourceId: 'candidate-1',
  }, ['wecom-binding.approve']), {
    view: 'wecom-onboarding', params: { candidateId: 'candidate-1' }, actionLabel: '去审核',
  })
  assert.deepEqual(resolveNotificationNavigation({
    type: 'WECOM_DIRECTORY_DEAD_LETTER', sourceType: 'WECOM_PERSON_ONBOARDING', sourceId: 'event-1',
  }, ['wecom-binding.manage']), {
    view: 'wecom-onboarding', params: { directoryEventId: 'event-1' }, actionLabel: '处理同步异常',
  })
})

test('日报、任务和KPI通知携带详情参数', () => {
  assert.deepEqual(resolveNotificationNavigation({ type: 'DAILY_REPORT_READY', sourceType: 'DAILY_REPORT', sourceId: 'report-1' }), {
    view: 'daily-report-detail', params: { reportId: 'report-1' }, actionLabel: '去填报',
  })
  assert.deepEqual(resolveNotificationNavigation({ type: 'TASK_RESULT_SUBMITTED', sourceType: 'TASK', sourceId: 'task-1' }), {
    view: 'tasks', params: { taskId: 'task-1' }, actionLabel: '去验收',
  })
  assert.deepEqual(resolveNotificationNavigation({ type: 'KPI_SCORECARD', sourceType: 'KPI_SCORECARD', sourceId: 'scorecard-1' }), {
    view: 'kpi-scorecard-detail', params: { scorecardId: 'scorecard-1' }, actionLabel: '查看考核',
  })
})

test('岗位事项提醒直达指定填报项', () => {
  assert.deepEqual(resolveNotificationNavigation({
    type: 'WORK_EXPECTATION_REMINDER', sourceType: 'WORK_EXPECTATION', sourceId: 'expectation-1',
  }), {
    view: 'my-work', params: { expectationId: 'expectation-1' }, actionLabel: '去完成',
  })
})

test('岗位治理通知直达岗位配置，员工通知只回到自己的工作台', () => {
  assert.deepEqual(resolveNotificationNavigation({
    type: 'POSITION_ACCESS_AUTO_SUSPENDED', sourceType: 'POSITION', sourceId: 'position-1',
  }, ['position-profile.read']), {
    view: 'organization', params: { tab: 'position', positionId: 'position-1' }, actionLabel: '检查岗位影响',
  })
  assert.deepEqual(resolveNotificationNavigation({
    type: 'POSITION_ACCESS_AUTO_SUSPENDED', sourceType: 'POSITION', sourceId: 'position-1',
  }, ['notification.read']), {
    view: 'workbench', actionLabel: '查看当前任职',
  })
})

test('企微员工本人结果不跳转到无权限的治理页面', () => {
  assert.deepEqual(resolveNotificationNavigation({
    type: 'WECOM_ONBOARDING_APPROVED', sourceType: 'WECOM_PERSON_ONBOARDING', sourceId: 'candidate-1',
  }, ['notification.read']), {
    view: 'workbench', actionLabel: '查看我的状态',
  })
  assert.equal(resolveNotificationNavigation({
    type: 'WECOM_ONBOARDING_PENDING', sourceType: 'WECOM_PERSON_ONBOARDING', sourceId: 'candidate-2',
  }, ['notification.read']), undefined)
})

test('未知通知不生成误导性的操作入口', () => {
  assert.equal(resolveNotificationNavigation({ type: 'SYSTEM_MESSAGE' }), undefined)
})

test('学院个人计划和管理提醒分别校验权限并进入对应页面', () => {
 const personal={type:'ACADEMY_STAGE_AVAILABLE',sourceType:'ACADEMY_PLAN',sourceId:'plan-1'};
 assert.deepEqual(resolveNotificationNavigation(personal,['academy.learn']),{view:'academy',params:{tab:'mine',id:'plan-1'},actionLabel:'查看成长计划'});
 assert.equal(resolveNotificationNavigation(personal,['notification.read']),undefined);
 const manager={type:'ACADEMY_MANAGER_OVERDUE',sourceType:'ACADEMY_PLAN',sourceId:'plan-1'};
 assert.deepEqual(resolveNotificationNavigation(manager,['academy.team-read']),{view:'academy',params:{tab:'team'},actionLabel:'查看团队学习'});
 assert.equal(resolveNotificationNavigation(manager,['academy.learn']),undefined);
});
