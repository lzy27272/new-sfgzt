import assert from 'node:assert/strict';
import { createHash, createHmac } from 'node:crypto';
import fs from 'node:fs';
import test from 'node:test';
import {
  validateConfig, parseDf, recipientQuery, validateRecipient, resolveRecipient,
  sendPersonalMessage, mondayPeriod, messageFor, runCheck,
} from './disk-alert.mjs';

const corp = 'test-corp';
const user = 'approved.personal';
const fingerprint = createHash('sha256').update(`${corp}:${user}`).digest('hex');
const config = {
  schemaVersion: 1, serverLabel: '43.136.184.38', mountPath: '/', thresholdPercent: 70,
  tenantId: '10000000-0000-0000-0000-000000000001',
  accountId: '20000000-0000-0000-0000-000000000001',
  bindingId: '30000000-0000-0000-0000-000000000001',
  loginName: 'approved-login', bindingFingerprint: fingerprint,
};
const env = {
  WECOM_ENABLED: 'true', WECOM_TENANT_ID: config.tenantId, WECOM_CORP_ID: corp,
  WECOM_CORP_SECRET: 'test-secret-never-print', WECOM_AGENT_ID: '1000002',
  DB_URL: 'jdbc:postgresql://127.0.0.1:5432/hotel_ai_os',
  DB_USERNAME: 'hotel_ai_os_app', DB_PASSWORD: 'example-db-secret-never-print',
};
const now = new Date('2026-09-28T01:00:00Z');
const usage = (percent = 70) => ({
  device: '/dev/vda2', mountPath: '/', totalBytes: 190109507584,
  usedBytes: 126000000000, availableBytes: 55000000000, usagePercent: percent,
});
const reply = value => ({ ok: true, text: async () => JSON.stringify(value) });

test('uses df rounded percentage, including reserved space', () => {
  const result = parseDf('Filesystem 1B-blocks Used Avail Use% Mounted on\n/dev/vda2 190109507584 40467832832 141787009024 23% /\n');
  assert.equal(result.usagePercent, 23);
  assert.equal(result.availableBytes, 141787009024);
  assert.notEqual(Math.round(result.usedBytes / result.totalBytes * 100), result.usagePercent);
});

test('rejects malformed, missing, multiple and non-root disk output', () => {
  for (const output of ['', 'header\n', 'header\n/dev/a 100 80 20 nope /',
    'header\n/dev/a 100 80 20 80% /data', 'header\n/dev/a 100 90 80 90% /',
    'header\n/dev/a 100 101 0 101% /', 'header\n/dev/a 0 0 0 0% /',
    'header\n/dev/a 100 80 20 80% /\n/dev/b 100 80 20 80% /']) {
    assert.throws(() => parseDf(output));
  }
});

test('config requires pinned tenant/account/binding/fingerprint and numeric threshold', () => {
  assert.equal(validateConfig(config), config);
  for (const change of [{ thresholdPercent: '70' }, { thresholdPercent: 0 }, { thresholdPercent: 100 },
    { accountId: "';delete from user_account;--" }, { bindingId: '' }, { loginName: 'a|b' },
    { bindingFingerprint: '' }, { serverLabel: 'host\ninject' }, { mountPath: '/tmp' }]) {
    assert.throws(() => validateConfig({ ...config, ...change }));
  }
});

test('recipient SQL is tenant scoped, identity pinned and read only', () => {
  const sql = recipientQuery(config, corp);
  assert.match(sql, /BEGIN READ ONLY/);
  assert.match(sql, /ROLLBACK/);
  assert.match(sql, /SET LOCAL app\.tenant_id=/);
  assert.match(sql, /ua\.status='ACTIVE'/);
  assert.match(sql, /b\.status='ACTIVE'/);
  assert.match(sql, /employment_status='ACTIVE'/);
  for (const value of [config.accountId, config.bindingId, config.bindingFingerprint, config.loginName]) {
    assert.ok(sql.includes(value));
  }
  assert.doesNotMatch(sql, /\b(INSERT|DELETE|UPDATE|ALTER|GRANT)\b/i);
});

test('recipient must be one active matching identity, never a broadcast', () => {
  assert.equal(validateRecipient([{ userid: user, fingerprint }], config, corp), user);
  for (const rows of [[], [{ userid: user, fingerprint }, { userid: user, fingerprint }],
    [{ userid: '@all', fingerprint }], [{ userid: 'a|b', fingerprint }],
    [{ userid: user + '.changed', fingerprint }], [{ userid: user, fingerprint: '0'.repeat(64) }]]) {
    assert.throws(() => validateRecipient(rows, config, corp));
  }
});

test('database lookup uses app account, a read-only session and no secret CLI arguments', () => {
  const actual = resolveRecipient(config, env, (program, args, options) => {
    assert.equal(program, '/usr/bin/psql');
    assert.ok(args.includes('hotel_ai_os_app'));
    assert.ok(args.includes('--no-password'));
    assert.ok(!JSON.stringify(args).includes(env.DB_PASSWORD));
    assert.equal(options.env.PGPASSWORD, env.DB_PASSWORD);
    assert.equal(options.env.WECOM_CORP_SECRET, undefined);
    assert.match(options.env.PGOPTIONS, /default_transaction_read_only=on/);
    assert.equal(options.timeout, 15000);
    return JSON.stringify([{ userid: user, fingerprint }]) + '\n';
  });
  assert.equal(actual, user);
});

test('supports the current directory HMAC without weakening the pinned identity check', () => {
  const key = Buffer.alloc(32, 7);
  const current = createHmac('sha256', key).update(`${corp}:${user}`).digest('hex');
  const currentConfig = { ...config, bindingFingerprint: current };
  const rows = [{ userid: user, fingerprint: current }];
  assert.equal(validateRecipient(rows, currentConfig, corp, key.toString('base64')), user);
  assert.throws(() => validateRecipient(rows, currentConfig, corp), /DIRECTORY_IDENTITY_KEY_REQUIRED/);
  assert.throws(() => validateRecipient(rows, currentConfig, corp, Buffer.alloc(32, 8).toString('base64')),
    /RECIPIENT_IDENTITY_CHANGED/);
  assert.throws(() => validateRecipient([{ userid: 'changed-user', fingerprint: current }], currentConfig,
    corp, key.toString('base64')), /RECIPIENT_IDENTITY_CHANGED/);
  assert.equal(resolveRecipient(currentConfig, { ...env, WECOM_DIRECTORY_ENCRYPTION_KEY: key.toString('base64') },
    () => JSON.stringify(rows)), user);
});

test('rejects wrong tenant, disabled messaging, privileged or remote database before any query', () => {
  for (const change of [{ WECOM_ENABLED: 'false' }, { WECOM_TENANT_ID: config.accountId },
    { DB_USERNAME: 'postgres' }, { DB_URL: 'jdbc:postgresql://remote.example/hotel_ai_os' },
    { DB_URL: 'jdbc:postgresql://127.0.0.1/other_db' }, { DB_PASSWORD: '' }]) {
    assert.throws(() => resolveRecipient(config, { ...env, ...change }, () => {
      assert.fail('query must not run');
    }));
  }
});

test('native lookup errors cannot leak passwords', () => {
  assert.throws(() => resolveRecipient(config, env, () => {
    throw new Error(env.DB_PASSWORD);
  }), error => error.message === 'RECIPIENT_LOOKUP_FAILED' && !error.message.includes(env.DB_PASSWORD));
});

test('weekly boundary is Monday midnight in Shanghai, not UTC', () => {
  assert.equal(mondayPeriod(new Date('2026-09-27T15:59:59Z')), '2026-09-21');
  assert.equal(mondayPeriod(new Date('2026-09-27T16:00:00Z')), '2026-09-28');
  assert.equal(mondayPeriod(new Date('2027-01-03T16:00:00Z')), '2027-01-04');
});

test('below 70 stays quiet without any database or messaging side effect', async () => {
  const result = await runCheck({ config, now, readUsage: () => usage(69),
    resolve: () => assert.fail('no lookup needed'), send: () => assert.fail('no message'),
    writeState: () => assert.fail('no delivery marker') });
  assert.equal(result.status, 'HEALTHY');
  assert.equal(result.notified, false);
});

test('70 itself triggers; completed alerts are deduplicated per week', async () => {
  let state = {};
  let deliveries = 0;
  const options = { config, now, readUsage: () => usage(70), resolve: () => user,
    readState: () => structuredClone(state), writeState: value => { state = structuredClone(value); },
    send: async (recipient, content) => { assert.equal(recipient, user); assert.match(content, /70%/); deliveries++; } };
  assert.equal((await runCheck(options)).status, 'ALERT_SENT');
  assert.equal((await runCheck(options)).status, 'ALREADY_SENT');
  assert.equal(deliveries, 1);
  assert.equal((await runCheck({ ...options, now: new Date('2026-10-05T01:00:00Z') })).status, 'ALERT_SENT');
  assert.equal(deliveries, 2);
});

test('dry run verifies recipient but never sends or changes delivery state', async () => {
  let lookups = 0;
  const result = await runCheck({ config, now, mode: 'dry-run', readUsage: () => usage(99),
    resolve: () => { lookups++; return user; }, send: () => assert.fail('no send'),
    writeState: () => assert.fail('no state write') });
  assert.equal(result.status, 'WOULD_ALERT');
  assert.equal(result.recipientVerified, true);
  assert.equal(lookups, 1);
});

test('one-time setup test is clearly labelled and does not consume weekly alert', async () => {
  let state = {};
  let deliveries = 0;
  const options = { config, now, mode: 'test', readUsage: () => usage(23), resolve: () => user,
    readState: () => structuredClone(state), writeState: value => { state = structuredClone(value); },
    send: async (_, content) => { assert.match(content, /非超限告警/); deliveries++; } };
  assert.equal((await runCheck(options)).status, 'TEST_SENT');
  assert.equal((await runCheck(options)).status, 'ALREADY_SENT');
  assert.equal(state.alert, undefined);
  assert.equal(deliveries, 1);
});

test('failed delivery is not reported as success; retry preserves exact payload', async () => {
  let state = {};
  let firstMessage;
  const options = { config, now, readUsage: () => usage(70), resolve: () => user,
    readState: () => structuredClone(state), writeState: value => { state = structuredClone(value); } };
  await assert.rejects(runCheck({ ...options, send: async (_, content) => {
    firstMessage = content; throw new Error('WECOM_REQUEST_FAILED');
  } }), /WECOM_REQUEST_FAILED/);
  assert.equal(state.alert.status, 'PENDING');
  const result = await runCheck({ ...options, now: new Date(now.getTime() + 60000), send: async (_, content) => {
    assert.equal(content, firstMessage);
  } });
  assert.equal(result.status, 'ALERT_SENT');
});

test('revoked binding blocks sends even at full capacity', async () => {
  await assert.rejects(runCheck({ config, now, readUsage: () => usage(100),
    resolve: () => { throw new Error('RECIPIENT_BINDING_NOT_UNIQUE_OR_INACTIVE'); },
    send: () => assert.fail('never send') }), /RECIPIENT_BINDING/);
});

test('personal message API has only a single recipient, pinned host and strict success checks', async () => {
  let calls = 0;
  const result = await sendPersonalMessage(user, 'test', env, async (url, options) => {
    assert.equal(url.origin, 'https://qyapi.weixin.qq.com');
    assert.equal(options.redirect, 'error');
    calls++;
    if (calls === 1) return reply({ errcode: 0, access_token: 'private-test-token' });
    const body = JSON.parse(options.body);
    assert.equal(body.touser, user);
    assert.equal(body.toparty, undefined);
    assert.equal(body.totag, undefined);
    assert.equal(body.enable_duplicate_check, 1);
    assert.equal(body.duplicate_check_interval, 14400);
    return reply({ errcode: 0, msgid: 'test-message-id', invaliduser: '' });
  });
  assert.deepEqual(result, { accepted: true });
  assert.equal(calls, 2);
});

test('rejects broadcasting and multi-recipient messages before network access', async () => {
  for (const recipient of ['@all', '@ALL', 'first|second', 'first\nsecond']) {
    await assert.rejects(sendPersonalMessage(recipient, 'test', env, () => assert.fail('no network')),
      /PERSONAL_RECIPIENT_REQUIRED/);
  }
});

test('invalid recipient or missing message id is not success, even with errcode zero', async () => {
  for (const answer of [{ errcode: 0, msgid: 'id', invaliduser: 'not-allowed' },
    { errcode: 0 }, { errcode: 81013, errmsg: env.WECOM_CORP_SECRET }]) {
    let calls = 0;
    await assert.rejects(sendPersonalMessage(user, 'test', env, async () =>
      ++calls === 1 ? reply({ errcode: 0, access_token: 'test-token' }) : reply(answer)),
    error => !error.message.includes(env.WECOM_CORP_SECRET));
  }
});

test('expired tokens refresh once, without changing message content', async () => {
  let calls = 0;
  const payloads = [];
  await sendPersonalMessage(user, 'test', env, async (_, options) => {
    calls++;
    if (calls % 2 === 1) return reply({ errcode: 0, access_token: `test-token-${calls}` });
    payloads.push(options.body);
    return reply(calls === 2 ? { errcode: 42001 } : { errcode: 0, msgid: 'id' });
  });
  assert.equal(calls, 4);
  assert.equal(payloads[0], payloads[1]);
});

test('HTTP and parsing errors never expose request secrets', async () => {
  for (const request of [
    async () => { throw new Error(`URL with ${env.WECOM_CORP_SECRET}`); },
    async () => ({ ok: false }),
    async () => ({ ok: true, text: async () => 'invalid-json' }),
  ]) {
    await assert.rejects(sendPersonalMessage(user, 'test', env, request),
      error => error.message === 'WECOM_REQUEST_FAILED' && !error.message.includes(env.WECOM_CORP_SECRET));
  }
});

test('Chinese messages fit API length and contain time, capacity, rule and no destructive action', () => {
  for (const isTest of [false, true]) {
    const content = messageFor(config, usage(100), now, isTest);
    assert.ok(Buffer.byteLength(content, 'utf8') <= 2048);
    assert.match(content, /2026\/09\/28 09:00/);
    assert.match(content, /每周一09:00/);
    assert.match(content, /不会自动删除/);
  }
});

test('systemd schedule is weekly Shanghai time; no changes to existing health/backup units', () => {
  const timer = fs.readFileSync(new URL('./hotel-ai-os-disk-alert.timer', import.meta.url), 'utf8');
  const service = fs.readFileSync(new URL('./hotel-ai-os-disk-alert.service', import.meta.url), 'utf8');
  assert.match(timer, /OnCalendar=Mon \*-\*-\* 09:00:00 Asia\/Shanghai/);
  assert.match(timer, /Persistent=true/);
  assert.match(service, /User=hotelai/);
  assert.match(service, /ProtectSystem=strict/);
  assert.match(service, /StateDirectoryMode=0700/);
  assert.doesNotMatch(service, /Restart=always/);
});
