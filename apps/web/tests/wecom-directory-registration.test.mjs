import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { validateDirectoryOnboardingRegistration } from '../src/features/wecom/directoryOnboardingRegistration.ts'

const entry = readFileSync(new URL('../src/features/wecom/WecomDirectoryOnboardingEntry.tsx', import.meta.url), 'utf8')
const registrationValidationSource = readFileSync(new URL('../src/features/wecom/directoryOnboardingRegistration.ts', import.meta.url), 'utf8')
const api = readFileSync(new URL('../src/features/wecom/directoryOnboardingApi.ts', import.meta.url), 'utf8')
const bindingAdministration = readFileSync(new URL('../src/features/wecom/WecomUserBindingAdministration.tsx', import.meta.url), 'utf8')
const invitationDialog = readFileSync(new URL('../src/features/wecom/WecomInvitationDialog.tsx', import.meta.url), 'utf8')
const onboardingAdministration = readFileSync(new URL('../src/features/wecom/WecomDirectoryOnboardingAdministration.tsx', import.meta.url), 'utf8')
const migration = readFileSync(new URL('../../../database/migrations/V41__wecom_directory_account_registration.sql', import.meta.url), 'utf8')
const manualInvitationMigration = readFileSync(new URL('../../../database/migrations/V42__manual_wecom_onboarding_invitation.sql', import.meta.url), 'utf8')
const onboardingDefaultsMigration = readFileSync(new URL('../../../database/migrations/V44__enable_reviewed_wecom_onboarding_positions.sql', import.meta.url), 'utf8')
const mobileRegistrationMigration = readFileSync(new URL('../../../database/migrations/V45__wecom_onboarding_mobile_registration.sql', import.meta.url), 'utf8')
const pendingPositionMigration = readFileSync(new URL('../../../database/migrations/V48__wecom_onboarding_pending_position_assignment.sql', import.meta.url), 'utf8')
const reusableInvitationMigration = readFileSync(new URL('../../../database/migrations/V49__reusable_wecom_onboarding_invitation.sql', import.meta.url), 'utf8')
const onboardingService = readFileSync(new URL('../../core-api/src/main/java/cn/sifangguan/hotelaios/integrations/wecom/WeComDirectoryOnboardingService.java', import.meta.url), 'utf8')
const onboardingAdministrationService = readFileSync(new URL('../../core-api/src/main/java/cn/sifangguan/hotelaios/integrations/wecom/WeComDirectoryOnboardingAdministrationService.java', import.meta.url), 'utf8')

test('verified new members register their account before choosing assignment', () => {
  assert.match(entry, /个人姓名/)
  assert.match(entry, /手机号/)
  assert.match(entry, /登录账号/)
  assert.match(entry, /登录密码/)
  assert.match(entry, /确认密码/)
  assert.match(entry, /行政人事或行政人事主管审核/)
  assert.match(entry, /审核前账号不可登录/)
})

test('registration submits confirmation but never persists secrets in browser storage', () => {
  assert.match(api, /passwordConfirmation/)
  assert.match(api, /sessionToken, displayName, mobile, loginName, password, passwordConfirmation/)
  assert.doesNotMatch(entry, /localStorage|sessionStorage/)
  assert.doesNotMatch(api, /localStorage|sessionStorage/)
})

test('registration validation explains short passwords instead of silently disabling submit', () => {
  const invalid = validateDirectoryOnboardingRegistration({
    requiresAccountRegistration: true,
    displayName: '测试员工',
    mobile: '13800138000',
    loginName: 'test.employee',
    secret: 'short12',
    secretConfirmation: 'short12',
    orgUnitId: 'hotel-id',
  })
  assert.equal(invalid.valid, false)
  assert.equal(invalid.errors.credential, '密码必须为10至128位')
  assert.match(entry, /disabled=\{busy\}/)
  assert.doesNotMatch(entry, /disabled=\{busy \|\| !selection\.orgUnitId \|\| !accountValid\}/)
})

test('valid registration and pending-position selection pass client validation', () => {
  const valid = validateDirectoryOnboardingRegistration({
    requiresAccountRegistration: true,
    displayName: '测试员工',
    mobile: '+86 138-0013-8000',
    loginName: 'test.employee',
    secret: 'correct-password',
    secretConfirmation: 'correct-password',
    orgUnitId: 'hotel-id',
  })
  assert.equal(valid.valid, true)
  assert.equal(valid.normalizedMobile, '13800138000')
  assert.deepEqual(valid.errors, {})
})

test('migration reserves open logins and grants both HR reviewer roles', () => {
  assert.match(migration, /ux_wecom_onboarding_open_login/)
  assert.match(migration, /requested_password_hash LIKE 'pbkdf2_sha256\$%'/)
  assert.match(migration, /'HR_ADMINISTRATION', 'HR_ADMINISTRATION_SUPERVISOR'/)
  assert.match(migration, /'wecom-onboarding\.review'/)
})

test('one reusable invitation entry performs independent reviewed mobile registrations', () => {
  assert.match(bindingAdministration, /createDirectoryOnboardingInvitation/)
  assert.match(bindingAdministration, /一键邀请/)
  assert.match(invitationDialog, /同一二维码在有效期内可供多名员工分别使用/)
  assert.match(invitationDialog, /员工提交不会使二维码失效；仅在超过有效时间后失效/)
  assert.match(invitationDialog, /填写手机号、姓名、账号、密码、集团总部\/门店和岗位/)
  assert.doesNotMatch(bindingAdministration, /已有中台账号绑定|无中台账号注册/)
  assert.doesNotMatch(bindingAdministration, /createEmployeeInvitation|employeeInviteForm/)
  assert.match(api, /directory-onboarding\/invitations/)
  assert.match(entry, /context\.requiresAccountRegistration \? '新员工注册'/)
})

test('mobile registration is validated and protected by database uniqueness', () => {
  assert.match(registrationValidationSource, /\^1\[3-9\]\\d\{9\}\$/)
  assert.match(mobileRegistrationMigration, /requested_mobile/)
  assert.match(mobileRegistrationMigration, /ux_user_account_tenant_mobile/)
  assert.match(mobileRegistrationMigration, /ux_wecom_onboarding_open_mobile/)
})

test('manual invitations store no employee profile before verified registration', () => {
  assert.match(manualInvitationMigration, /invitation_source/)
  assert.match(manualInvitationMigration, /MANUAL_LINK/)
  assert.match(manualInvitationMigration, /invitation_created_by/)
})

test('reusable invitation state is separated from every employee application', () => {
  assert.match(reusableInvitationMigration, /CREATE TABLE wecom_open_onboarding_invitation/)
  assert.match(reusableInvitationMigration, /ADD COLUMN open_invitation_id UUID/)
  assert.match(reusableInvitationMigration, /FORCE ROW LEVEL SECURITY/)
  assert.match(onboardingAdministrationService, /insert into wecom_open_onboarding_invitation/)
  assert.match(onboardingService, /insert into wecom_person_onboarding/)
  assert.match(onboardingService, /open_invitation_id/)
  assert.match(onboardingService, /expires_at > now\(\)/)
})

test('registration hides unavailable positions and supports reviewer assignment', () => {
  assert.match(entry, /\.filter\(\(position\) => position\.selectable\)/)
  assert.match(entry, /disabled=\{!hasOrganizationOptions\}/)
  assert.doesNotMatch(entry, /disabled=\{!item\.selectable\}/)
  assert.doesNotMatch(entry, /unavailableReason/)
  assert.match(entry, /岗位待分配/)
  assert.match(entry, /selection\.positionId \|\| undefined/)
  assert.match(api, /positionId: positionId \|\| null/)
  assert.match(api, /assignment-options/)
  assert.match(onboardingAdministration, /审核分配岗位/)
  assert.match(onboardingAdministration, /请先为员工分配具体岗位再确认启用/)
  assert.match(pendingPositionMigration, /status <> 'APPROVED' OR requested_position_id IS NOT NULL/)
  assert.match(onboardingDefaultsMigration, /FRONT_DESK/)
  assert.match(onboardingDefaultsMigration, /protected_permission\.delegable_to_position = false/)
  assert.doesNotMatch(onboardingDefaultsMigration, /GROUP_CHAIRMAN|GROUP_GENERAL_MANAGER|GROUP_VICE_PRESIDENT|HR_KPI_ADMIN|PLATFORM_ADMIN|OTA_OPERATION_MANAGER/)
})

test('registration supports group headquarters while protected group roles stay excluded', () => {
  assert.match(api, /unitType: 'GROUP' \| 'HOTEL'/)
  assert.match(entry, /选择组织/)
  assert.match(entry, /请选择集团总部或门店/)
  assert.match(entry, /item\.unitType === 'GROUP' \? '集团总部'/)
  assert.match(entry, /集团受保护岗位不能自行申请，需由具备任职管理权限的管理员审核分配/)
  assert.match(onboardingService, /position\.job_family = 'GROUP_MANAGEMENT'/)
  assert.match(onboardingService, /authorization_scope_type <> 'TENANT'/)
  assert.match(onboardingService, /protected_permission\.delegable_to_position = false/)
})

test('review assignment keeps the server-provided options and explains management authority', () => {
  assert.match(onboardingAdministration, /reviewOptions\?\.positions\.map/)
  assert.match(onboardingAdministration, /总部受保护岗位须由具备任职管理权限的管理员分配/)
  assert.match(onboardingAdministration, /普通审核员仍受原有权限限制/)
  assert.doesNotMatch(onboardingAdministration, /租户级或不可委派的受保护岗位仍需在组织与权限中直接分配/)
})

test('hotel-direct positions do not repeat the hotel name in the position selector', () => {
  assert.match(entry, /department\.id === organization\?\.id \? position\.name/)
  assert.match(entry, /`\$\{department\.name\} · \$\{position\.name\}`/)
})
