#!/usr/bin/env node
// Independent, read-only capacity check. Never deletes files or changes business data.
import { execFileSync } from 'node:child_process';
import { createHash, createHmac, randomUUID } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const API = 'https://qyapi.weixin.qq.com';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const DEFAULT_CONFIG = '/etc/hotel-ai-os/disk-alert.json';
const DEFAULT_STATE = '/var/lib/hotel-ai-os-disk-alert';

function requireThat(condition, code) {
  if (!condition) throw new Error(code);
}

export function validateConfig(config) {
  requireThat(config?.schemaVersion === 1, 'CONFIG_SCHEMA_INVALID');
  requireThat(config.mountPath === '/', 'ONLY_ROOT_FILESYSTEM_SUPPORTED');
  requireThat(Number.isInteger(config.thresholdPercent)
    && config.thresholdPercent >= 1 && config.thresholdPercent <= 99, 'THRESHOLD_INVALID');
  for (const key of ['tenantId', 'accountId', 'bindingId']) {
    requireThat(UUID.test(config[key] ?? ''), `CONFIG_${key.toUpperCase()}_INVALID`);
  }
  requireThat(/^[A-Za-z0-9_.@-]{1,120}$/.test(config.loginName ?? ''), 'LOGIN_NAME_INVALID');
  requireThat(/^[a-f0-9]{64}$/.test(config.bindingFingerprint ?? ''), 'FINGERPRINT_INVALID');
  requireThat(/^[A-Za-z0-9_.:-]{1,80}$/.test(config.serverLabel ?? ''), 'SERVER_LABEL_INVALID');
  return config;
}

export function parseDf(output) {
  const lines = output.trim().split(/\r?\n/);
  requireThat(lines.length === 2, 'DISK_RESULT_INVALID');
  const cells = lines[1].trim().split(/\s+/);
  requireThat(cells.length === 6 && /^\d+%$/.test(cells[4]) && cells[5] === '/', 'DISK_RESULT_INVALID');
  const [totalBytes, usedBytes, availableBytes] = cells.slice(1, 4).map(Number);
  const usagePercent = Number(cells[4].slice(0, -1));
  requireThat([totalBytes, usedBytes, availableBytes].every(n => Number.isSafeInteger(n) && n >= 0)
    && totalBytes > 0 && usedBytes <= totalBytes && availableBytes <= totalBytes
    && usedBytes + availableBytes <= totalBytes
    && usagePercent >= 0 && usagePercent <= 100, 'DISK_RESULT_INVALID');
  // Use the same rounded percentage as `df`, including filesystem-reserved space.
  return { device: cells[0], mountPath: '/', totalBytes, usedBytes, availableBytes, usagePercent };
}

export function readDiskUsage() {
  try {
    return parseDf(execFileSync('/usr/bin/df', [
      '--block-size=1', '--output=source,size,used,avail,pcent,target', '/',
    ], { encoding: 'utf8', timeout: 10000, maxBuffer: 16384,
      env: { PATH: '/usr/bin:/bin', LC_ALL: 'C' }, stdio: ['ignore', 'pipe', 'pipe'] }));
  } catch {
    throw new Error('DISK_READ_FAILED');
  }
}

function literal(value) { return `'${String(value).replaceAll("'", "''")}'`; }

export function recipientQuery(config, corpId) {
  return `BEGIN READ ONLY;
SET LOCAL statement_timeout='5s';
SET LOCAL lock_timeout='1s';
SET LOCAL app.tenant_id=${literal(config.tenantId)};
SELECT coalesce(json_agg(recipient), '[]'::json) FROM (
  SELECT b.wecom_user_id AS userid, b.user_id_fingerprint AS fingerprint
  FROM user_account ua
  JOIN wecom_user_binding b ON b.tenant_id=ua.tenant_id AND b.account_id=ua.id
  WHERE ua.tenant_id=${literal(config.tenantId)}::uuid
    AND ua.id=${literal(config.accountId)}::uuid
    AND ua.login_name=${literal(config.loginName)} AND ua.status='ACTIVE'
    AND b.id=${literal(config.bindingId)}::uuid AND b.status='ACTIVE'
    AND b.corp_id=${literal(corpId)}
    AND b.user_id_fingerprint=${literal(config.bindingFingerprint)}
    AND EXISTS (SELECT 1 FROM employee e WHERE e.tenant_id=ua.tenant_id
                AND e.account_id=ua.id AND e.employment_status='ACTIVE')
  LIMIT 2
) recipient;
ROLLBACK;
`;
}

export function validateRecipient(rows, config, corpId, directoryKey = '') {
  requireThat(Array.isArray(rows) && rows.length === 1, 'RECIPIENT_BINDING_NOT_UNIQUE_OR_INACTIVE');
  const { userid, fingerprint } = rows[0];
  requireThat(typeof userid === 'string' && /^[A-Za-z0-9_.@-]{1,128}$/.test(userid)
    && userid.toLowerCase() !== '@all', 'PERSONAL_RECIPIENT_REQUIRED');
  requireThat(fingerprint === config.bindingFingerprint, 'RECIPIENT_IDENTITY_CHANGED');
  // Existing manual bindings use SHA256; directory onboarding uses the core API's
  // WeComDirectorySecretCodec HMAC. Verify the real identifier in either supported format.
  const identity = `${corpId}:${userid}`;
  const legacy = createHash('sha256').update(identity).digest('hex');
  if (legacy !== fingerprint) {
    requireThat(/^[A-Za-z0-9+/]{43}=$/.test(directoryKey), 'DIRECTORY_IDENTITY_KEY_REQUIRED');
    const key = Buffer.from(directoryKey, 'base64');
    requireThat(key.length === 32, 'DIRECTORY_IDENTITY_KEY_REQUIRED');
    const current = createHmac('sha256', key).update(identity).digest('hex');
    requireThat(current === fingerprint, 'RECIPIENT_IDENTITY_CHANGED');
  }
  return userid;
}

export function resolveRecipient(config, env = process.env, execute = execFileSync) {
  requireThat(env.WECOM_ENABLED === 'true' && env.WECOM_TENANT_ID === config.tenantId,
    'WECOM_DISABLED_OR_TENANT_MISMATCH');
  requireThat(/^[A-Za-z0-9_-]{1,128}$/.test(env.WECOM_CORP_ID ?? ''), 'WECOM_CORP_ID_INVALID');
  requireThat(env.DB_USERNAME === 'hotel_ai_os_app' && Boolean(env.DB_PASSWORD), 'READONLY_APP_DB_CONFIG_REQUIRED');
  let database;
  try { database = new URL(String(env.DB_URL).replace(/^jdbc:/, '')); }
  catch { throw new Error('LOCAL_DATABASE_REQUIRED'); }
  requireThat(database.protocol === 'postgresql:'
    && ['127.0.0.1', 'localhost', '[::1]'].includes(database.hostname)
    && database.pathname === '/hotel_ai_os' && !database.username && !database.password
    && !database.search && !database.hash, 'LOCAL_DATABASE_REQUIRED');
  let rows;
  try {
    const output = execute('/usr/bin/psql', [
      '-X', '-q', '-A', '-t', '--no-password', '--set=ON_ERROR_STOP=1',
      '--host', database.hostname.replace(/^\[|\]$/g, ''), '--port', database.port || '5432',
      '--username', env.DB_USERNAME, '--dbname', 'hotel_ai_os',
    ], {
      input: recipientQuery(config, env.WECOM_CORP_ID), encoding: 'utf8', timeout: 15000,
      maxBuffer: 32768, stdio: ['pipe', 'pipe', 'pipe'],
      env: { PATH: '/usr/bin:/bin', LC_ALL: 'C.UTF-8', PGCLIENTENCODING: 'UTF8',
        PGPASSWORD: env.DB_PASSWORD, PGCONNECT_TIMEOUT: '5', PGAPPNAME: 'sfg-disk-alert',
        PGOPTIONS: '-c default_transaction_read_only=on -c statement_timeout=5000 -c lock_timeout=1000' },
    });
    rows = JSON.parse(output.trim());
  } catch {
    // Native errors may embed the whole command/environment. Never log them.
    throw new Error('RECIPIENT_LOOKUP_FAILED');
  }
  return validateRecipient(rows, config, env.WECOM_CORP_ID, env.WECOM_DIRECTORY_ENCRYPTION_KEY);
}

async function apiRequest(route, query, body, request) {
  const url = new URL(route, API);
  url.search = new URLSearchParams(query).toString();
  let response;
  let data;
  try {
    response = await request(url, {
      method: body ? 'POST' : 'GET', redirect: 'error', signal: AbortSignal.timeout(12000),
      headers: body ? { 'Content-Type': 'application/json' } : {},
      ...(body ? { body: JSON.stringify(body) } : {}),
    });
    requireThat(response.ok, 'HTTP_FAILURE');
    const text = await response.text();
    requireThat(text.length <= 65536, 'RESPONSE_TOO_LARGE');
    data = JSON.parse(text);
  } catch {
    // In particular, do not include a request URL, corp secret, token, or provider errmsg.
    throw new Error('WECOM_REQUEST_FAILED');
  }
  requireThat(data && typeof data === 'object' && !Array.isArray(data)
    && Number.isInteger(data.errcode), 'WECOM_RESPONSE_INVALID');
  return data;
}

export async function sendPersonalMessage(userid, content, env = process.env, request = fetch) {
  requireThat(typeof userid === 'string' && /^[A-Za-z0-9_.@-]{1,128}$/.test(userid)
    && userid.toLowerCase() !== '@all', 'PERSONAL_RECIPIENT_REQUIRED');
  requireThat(env.WECOM_ENABLED === 'true' && Boolean(env.WECOM_CORP_SECRET)
    && /^[A-Za-z0-9_-]{1,128}$/.test(env.WECOM_CORP_ID ?? '')
    && /^[1-9]\d*$/.test(env.WECOM_AGENT_ID ?? '')
    && Number.isSafeInteger(Number(env.WECOM_AGENT_ID)), 'WECOM_CONFIG_INVALID');
  requireThat(typeof content === 'string' && Buffer.byteLength(content, 'utf8') <= 2048,
    'MESSAGE_LENGTH_INVALID');
  const payload = {
    touser: userid, msgtype: 'text', agentid: Number(env.WECOM_AGENT_ID), text: { content },
    safe: 0, enable_duplicate_check: 1, duplicate_check_interval: 14400,
  };
  for (let attempt = 0; attempt < 2; attempt++) {
    const token = await apiRequest('/cgi-bin/gettoken', {
      corpid: env.WECOM_CORP_ID, corpsecret: env.WECOM_CORP_SECRET,
    }, null, request);
    requireThat(token.errcode === 0, `WECOM_TOKEN_ERROR_${token.errcode}`);
    requireThat(typeof token.access_token === 'string' && token.access_token.length > 0,
      'WECOM_TOKEN_INVALID');
    const result = await apiRequest('/cgi-bin/message/send', { access_token: token.access_token }, payload, request);
    if (attempt === 0 && [40014, 42001].includes(result.errcode)) continue;
    requireThat(result.errcode === 0, `WECOM_SEND_ERROR_${result.errcode}`);
    requireThat(!result.invaliduser && !result.invalidparty && !result.invalidtag,
      'WECOM_RECIPIENT_REJECTED');
    requireThat(typeof result.msgid === 'string' && result.msgid.trim().length > 0, 'WECOM_MESSAGE_ID_MISSING');
    return { accepted: true };
  }
  throw new Error('WECOM_TOKEN_REFRESH_FAILED');
}

export function mondayPeriod(now) {
  const localDate = new Date(now.getTime() + 8 * 3600000);
  localDate.setUTCDate(localDate.getUTCDate() - ((localDate.getUTCDay() + 6) % 7));
  return localDate.toISOString().slice(0, 10);
}

export function messageFor(config, usage, now, test = false) {
  const time = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).format(now);
  const gib = value => (value / 1073741824).toFixed(1);
  return [
    test ? '【容量预警通道测试｜非超限告警】' : '【云服务器存储容量预警】',
    `服务器：${config.serverLabel}（根目录磁盘）`, `检查时间：${time}（北京时间）`,
    `当前占用：${usage.usagePercent}%；预警阈值：${config.thresholdPercent}%`,
    `已用：${gib(usage.usedBytes)} GiB；总量：${gib(usage.totalBytes)} GiB`,
    `剩余可用：${gib(usage.availableBytes)} GiB`,
    `接收账号：${config.loginName}`,
    '检查计划：每周一09:00（北京时间），达到或超过阈值时发送个人预警。',
    test ? '这是一条通道验证消息，无需按超限处理；正式检查正常时不发送通知。'
      : '请安排容量检查，评估清理过期数据或扩容；清理前请先核对保留规则和有效备份。',
    '本监测只查询和提醒，不会自动删除任何数据。',
  ].join('\n');
}

function readJson(file, missing = null) {
  try {
    const item = fs.lstatSync(file);
    requireThat(item.isFile() && !item.isSymbolicLink() && item.size <= 65536, 'STATE_FILE_INVALID');
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (error) {
    if (error.code === 'ENOENT' && missing !== null) return missing;
    throw new Error('CONFIG_OR_STATE_READ_FAILED');
  }
}

function writeJson(file, value) {
  const directory = path.dirname(file);
  const item = fs.lstatSync(directory);
  requireThat(item.isDirectory() && !item.isSymbolicLink(), 'STATE_DIRECTORY_INVALID');
  const temporary = path.join(directory, `.disk-alert-${randomUUID()}.tmp`);
  let descriptor;
  try {
    descriptor = fs.openSync(temporary, 'wx', 0o600);
    fs.writeFileSync(descriptor, JSON.stringify(value, null, 2) + '\n', 'utf8');
    fs.fsyncSync(descriptor);
    fs.closeSync(descriptor);
    descriptor = undefined;
    fs.renameSync(temporary, file);
  } finally {
    if (descriptor !== undefined) fs.closeSync(descriptor);
    if (fs.existsSync(temporary)) fs.unlinkSync(temporary); // Only our own newly-created temporary file.
  }
}

export async function runCheck({ config, mode = 'check', now = new Date(), env = process.env,
  readUsage = readDiskUsage, resolve = resolveRecipient, send = sendPersonalMessage,
  readState = () => ({}), writeState = () => {} }) {
  validateConfig(config);
  requireThat(['check', 'dry-run', 'test'].includes(mode), 'MODE_INVALID');
  const usage = readUsage();
  const report = { checkedAt: now.toISOString(), mode, thresholdPercent: config.thresholdPercent, ...usage };
  if (mode === 'check' && usage.usagePercent < config.thresholdPercent) {
    return { ...report, status: 'HEALTHY', notified: false };
  }
  const userid = resolve(config, env);
  if (mode === 'dry-run') {
    return { ...report, status: usage.usagePercent >= config.thresholdPercent ? 'WOULD_ALERT' : 'HEALTHY',
      recipientVerified: true, notified: false };
  }
  const state = readState();
  const configKey = createHash('sha256').update(JSON.stringify(config)).digest('hex');
  const eventKey = `${configKey}:${mode === 'test' ? 'setup-test' : mondayPeriod(now)}`;
  const previous = mode === 'test' ? state.test : state.alert;
  if (previous?.eventKey === eventKey && previous.status === 'SENT') {
    return { ...report, status: 'ALREADY_SENT', notified: false };
  }
  // A transport error could occur after delivery. Reuse the exact payload on a manual retry;
  // WeCom duplicate checking and a completed weekly marker reduce duplicate alerts.
  const content = previous?.eventKey === eventKey ? previous.content : messageFor(config, usage, now, mode === 'test');
  const slot = mode === 'test' ? 'test' : 'alert';
  state[slot] = { eventKey, status: 'PENDING', content, attemptedAt: now.toISOString() };
  writeState(state);
  await send(userid, content, env);
  state[slot].status = 'SENT';
  state[slot].sentAt = now.toISOString();
  writeState(state);
  return { ...report, status: mode === 'test' ? 'TEST_SENT' : 'ALERT_SENT', notified: true };
}

async function main() {
  const args = process.argv.slice(2);
  let mode = 'check';
  let configPath = DEFAULT_CONFIG;
  let stateDirectory = DEFAULT_STATE;
  try {
    for (let index = 0; index < args.length; index += 2) {
      requireThat(Boolean(args[index + 1]), 'ARGUMENT_INVALID');
      if (args[index] === '--mode') mode = args[index + 1];
      else if (args[index] === '--config') configPath = args[index + 1];
      else if (args[index] === '--state-directory') stateDirectory = args[index + 1];
      else throw new Error('ARGUMENT_INVALID');
    }
    const config = validateConfig(readJson(configPath));
    const stateFile = path.join(stateDirectory, 'delivery-state.json');
    const result = await runCheck({ config, mode,
      readState: () => readJson(stateFile, {}), writeState: value => writeJson(stateFile, value) });
    if (mode !== 'dry-run') writeJson(path.join(stateDirectory, 'last-result.json'), result);
    console.log(JSON.stringify(result));
  } catch (error) {
    // No stack/cause/provider message: they can contain credentials or recipient identifiers.
    const code = /^[A-Z][A-Z0-9_]{1,100}$/.test(error.message) ? error.message : 'DISK_ALERT_FAILED';
    const result = { checkedAt: new Date().toISOString(), mode, status: 'FAILED', errorCode: code };
    try { if (mode !== 'dry-run') writeJson(path.join(stateDirectory, 'last-result.json'), result); } catch { /* stderr still records failure */ }
    console.error(JSON.stringify(result));
    process.exitCode = 1;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  await main();
}
