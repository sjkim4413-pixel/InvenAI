'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const [cliRoot, mode = 'check'] = process.argv.slice(2);
assert(cliRoot && ['check', 'apply', 'verify'].includes(mode), 'Use FIREBASE_CLI_LIB_DIRECTORY check|apply|verify');
const plan = JSON.parse(fs.readFileSync(path.join(__dirname, 'inventory_plan.json'), 'utf8'));
assert.equal(plan.project, 'warehouseqa-8c262');
assert.equal(plan.database, '(default)');
assert.equal(plan.collection, 'inventory');
assert.equal(plan.companyCode, 'ABC-002');
assert.equal(plan.warehouse, 'AI 상품·위치 비교 테스트 창고');
assert.equal(plan.records.length, 3);
assert.equal(new Set(plan.records.map(record => record.id)).size, 3);
const locations = new Map([['HPX-111','QAC02-5F-01'],['HPX-222','QAC02-5F-02'],['HPX-333','QAC03-5F-01']]);

const { Client } = require(path.join(cliRoot, 'apiv2.js'));
const { getProjectDefaultAccount } = require(path.join(cliRoot, 'auth.js'));
const { requireAuth } = require(path.join(cliRoot, 'requireAuth.js'));
const client = new Client({ urlPrefix: 'https://firestore.googleapis.com', apiVersion: 'v1', auth: true });
const database = `projects/${plan.project}/databases/${plan.database}`;
const documents = `${database}/documents`;

function encode(value) {
  if (typeof value === 'string') return { stringValue: value };
  if (typeof value === 'boolean') return { booleanValue: value };
  if (Number.isSafeInteger(value)) return { integerValue: String(value) };
  if (Array.isArray(value)) return { arrayValue: { values: value.map(encode) } };
  throw new TypeError('Unexpected fixture field type');
}

function decode(value) {
  if ('stringValue' in value) return value.stringValue;
  if ('booleanValue' in value) return value.booleanValue;
  if ('integerValue' in value) return Number(value.integerValue);
  if ('doubleValue' in value) return value.doubleValue;
  if ('arrayValue' in value) return (value.arrayValue.values ?? []).map(decode);
  return null;
}

const targets = plan.records.map(({ id, data }) => {
  assert(locations.has(data.itemCode));
  assert.equal(id, `${plan.companyCode}_${data.itemCode}`);
  assert.equal(data.companyCode, plan.companyCode);
  assert.equal(data.warehouse, plan.warehouse);
  assert.equal(data.isTestData, true);
  assert.equal(data.dataSource, 'SIMULATED_AI_IMAGE_ERP');
  assert.equal(data.quantity, 1);
  assert.equal(data.quantityUnit, 'PACKAGING_GROUP');
  assert.equal(data.status, 'NORMAL');
  assert.equal(data.photoLocationLabel, data.location);
  assert.equal(data.location, locations.get(data.itemCode));
  assert.equal(data.photoReference, 'compare_01_normal.png');
  assert.equal(data.alternatePhotoReference, 'compare_02_misplaced.png');
  assert.match(data.photoSha256, /^[a-f0-9]{64}$/);
  assert.match(data.alternatePhotoSha256, /^[a-f0-9]{64}$/);
  return { name: `${documents}/inventory/${id}`,
           fields: Object.fromEntries(Object.entries(data).map(([key, value]) => [key, encode(value)])) };
});
const bySku = new Map(plan.records.map((record, index) => [record.data.itemCode, targets[index].name]));

async function readTargets() {
  const response = await client.post(`${documents}:batchGet`, { documents: targets.map(target => target.name) });
  assert.equal(response.body.length, targets.length, 'Incomplete fixture lookup');
  const result = new Map(response.body.map(row => [row.found?.name ?? row.missing, row.found ?? null]));
  assert.equal(result.size, targets.length);
  for (const target of targets) assert(result.has(target.name), 'Missing preflight response');
  return result;
}

function assertSame(target, existing) {
  for (const [field, expected] of Object.entries(target.fields)) {
    assert.deepEqual(existing.fields[field], expected, `Existing fixture differs: ${target.name.split('/').at(-1)} / ${field}`);
  }
}

async function main() {
  const account = getProjectDefaultAccount(process.cwd());
  assert(account, 'No signed-in Firebase CLI account');
  await requireAuth({ project: plan.project, user: account.user, tokens: account.tokens, nonInteractive: true });
  const info = await client.get(database);
  assert.equal(info.body.name, database);
  assert.equal(info.body.type, 'FIRESTORE_NATIVE');
  assert(['STANDARD', 'DATABASE_EDITION_UNSPECIFIED', undefined].includes(info.body.databaseEdition), 'Unexpected Firestore edition');
  assert.equal(info.body.freeTier, true, 'Expected existing free-tier database');
  const response = await client.post(`${documents}:runQuery`, { structuredQuery: {
    from: [{ collectionId: 'inventory' }],
    select: { fields: ['itemCode', 'location', 'warehouse'].map(fieldPath => ({ fieldPath })) },
    where: { fieldFilter: { field: { fieldPath: 'companyCode' }, op: 'EQUAL', value: { stringValue: plan.companyCode } } },
    limit: 10001,
  } });
  const company = response.body.flatMap(row => row.document ? [row.document] : []);
  assert(company.length <= 10000, 'Company inventory exceeds preflight limit');
  for (const document of company) {
    const sku = document.fields?.itemCode?.stringValue;
    const location = document.fields?.location?.stringValue;
    const target = bySku.get(sku);
    assert(!target || target === document.name, `Fixture SKU has another document ID: ${sku}`);
    if (document.fields?.warehouse?.stringValue === plan.warehouse) {
      const entry = plan.records.find(record => record.data.location === location);
      assert(entry && document.name === `${documents}/inventory/${entry.id}`, `Unexpected comparison location: ${location}`);
    }
  }
  const before = await readTargets();
  const missing = targets.filter(target => !before.get(target.name));
  for (const target of targets) if (before.get(target.name)) assertSame(target, before.get(target.name));
  console.log(JSON.stringify({ stage: 'preflight', mode, project: plan.project, warehouse: plan.warehouse,
                              companyDocuments: company.length, pending: missing.length, identical: targets.length - missing.length }));
  if (mode === 'check') return;
  let created = 0;
  if (mode === 'apply' && missing.length) {
    const result = await client.post(`${documents}:commit`, { writes: missing.map(target => ({
      update: { ...target, fields: { ...target.fields, updatedAtTimestamp: encode(Date.now()) } },
      updateTransforms: [{ fieldPath: 'updatedAt', setToServerValue: 'REQUEST_TIME' }],
      currentDocument: { exists: false },
    })) });
    assert.equal(result.body.writeResults.length, missing.length);
    created = missing.length;
  }
  const after = await readTargets();
  const readback = [];
  for (const target of targets) {
    const found = after.get(target.name);
    assert(found, 'Fixture missing during readback');
    assertSame(target, found);
    readback.push([target.name.split('/').at(-1), Object.fromEntries(Object.entries(found.fields).map(([key, value]) => [key, decode(value)]))]);
  }
  fs.writeFileSync(path.join(__dirname, 'inventory_readback.json'), JSON.stringify(readback, null, 2) + '\n');
  console.log(JSON.stringify({ stage: 'verified', created, verified: readback.length }));
  if (mode === 'verify') {
    const complete = await client.post(`${documents}:runQuery`, { structuredQuery: {
      from: [{ collectionId: 'inventory' }],
      where: { fieldFilter: { field: { fieldPath: 'companyCode' }, op: 'EQUAL', value: encode(plan.companyCode) } },
      limit: 10001,
    } });
    const records = complete.body.flatMap(row => row.document ? [[row.document.name.split('/').at(-1),
      Object.fromEntries(Object.entries(row.document.fields).map(([key, value]) => [key, decode(value)]))]] : []);
    assert(records.length <= 10000);
    const { spawnSync } = require('node:child_process');
    const backend = path.resolve(__dirname, '../../app/src/main/java/com/example/myapp/F03_py');
    const check = spawnSync(path.join(backend, '.venv/Scripts/python.exe'), ['-B', path.join(__dirname, 'verify_company.py')], {
      input: JSON.stringify(records), encoding: 'utf8', cwd: backend, windowsHide: true,
      env: { ...process.env, PYTHONIOENCODING: 'utf-8' },
    });
    assert.equal(check.status, 0, (check.stderr ?? '').slice(-1200));
    fs.writeFileSync(path.join(__dirname, 'verification_report.json'), check.stdout);
    console.log(check.stdout.trim());
  }
}

main().catch(error => {
  console.error(String(error.message ?? 'Fixture registration failed').split('\n')[0].slice(0, 550));
  process.exitCode = 1;
});
