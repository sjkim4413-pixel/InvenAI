'use strict';
// Keys and HTTP response bodies are never logged. The dedicated account receives only FCM send access.
const fs = require('node:fs'), path = require('node:path');
async function main() {
  const lib = process.argv[2], project = 'warehouseqa-8c262';
  const root = path.resolve(__dirname, '../..'), folder = path.join(root, '.secrets');
  const target = path.join(folder, 'fcm-sender.json');
  const email = `invenai-fcm-sender@${project}.iam.gserviceaccount.com`;
  const member = `serviceAccount:${email}`, role = 'roles/firebasecloudmessaging.admin';
  const {getProjectDefaultAccount} = require(path.join(lib, 'auth.js'));
  const {requireAuth} = require(path.join(lib, 'requireAuth.js'));
  const {getAccessToken} = require(path.join(lib, 'apiv2.js'));
  await requireAuth({project, ...getProjectDefaultAccount(root)});
  const token = await getAccessToken();
  async function api(url, method='GET', body) {
    const result = await fetch(url, {method, headers:{Authorization:`Bearer ${token}`, 'Content-Type':'application/json'},
      body:body === undefined ? undefined : JSON.stringify(body), signal:AbortSignal.timeout(20000)});
    const data = await result.json();
    if (!result.ok) {const error = Error(`Cloud setup HTTP ${result.status}`);error.status=result.status;throw error;}
    return data;
  }
  const accountUrl = `https://iam.googleapis.com/v1/projects/${project}/serviceAccounts/${email}`;
  let createdAccount = false;
  try {await api(accountUrl);} catch(error) {
    if(error.status !== 404) throw error;
    await api(`https://iam.googleapis.com/v1/projects/${project}/serviceAccounts`, 'POST', {
      accountId:'invenai-fcm-sender', serviceAccount:{displayName:'InvenAI device notification sender',
        description:'Send only FCM data notifications from the existing InvenAI PC server; no inventory access.'}});
    createdAccount = true;
  }
  const policyUrl = `https://cloudresourcemanager.googleapis.com/v1/projects/${project}`;
  const policy = await api(policyUrl+':getIamPolicy','POST',{options:{requestedPolicyVersion:3}});
  const otherRoles = (policy.bindings||[]).filter(binding => (binding.members||[]).includes(member) && binding.role !== role);
  if (otherRoles.length) throw Error('The sender account already has unrelated project roles; manual review required.');
  let binding = (policy.bindings||[]).find(binding => binding.role === role && !binding.condition);
  if (!binding || !binding.members.includes(member)) {
    if (!binding) {binding={role,members:[]};(policy.bindings ||= []).push(binding);}
    binding.members.push(member);
    // The original etag protects concurrent IAM changes; all unrelated bindings are preserved.
    await api(policyUrl+':setIamPolicy','POST',{policy});
  }
  const verified = await api(policyUrl+':getIamPolicy','POST',{options:{requestedPolicyVersion:3}});
  const granted = (verified.bindings||[]).filter(binding => (binding.members||[]).includes(member));
  if (granted.length !== 1 || granted[0].role !== role || granted[0].condition) throw Error('Unexpected sender access.');
  fs.mkdirSync(folder,{recursive:true,mode:0o700});
  let createdKey = false;
  if (fs.existsSync(target)) {
    const existing = JSON.parse(fs.readFileSync(target,'utf8'));
    if (existing.client_email !== email || existing.project_id !== project) throw Error('Existing credential belongs to another sender.');
  } else {
    // Native fetch deliberately avoids Firebase CLI response-body debug logging for this secret response.
    const key = await api(accountUrl+'/keys','POST',{privateKeyType:'TYPE_GOOGLE_CREDENTIALS_FILE',keyAlgorithm:'KEY_ALG_RSA_2048'});
    try {
      const content = Buffer.from(key.privateKeyData,'base64');
      const parsed = JSON.parse(content.toString('utf8'));
      if (parsed.client_email !== email || parsed.project_id !== project) throw Error('Unexpected generated credential.');
      fs.writeFileSync(target,content,{flag:'wx',mode:0o600});
      createdKey = true;
    } catch(error) {
      await api('https://iam.googleapis.com/v1/'+key.name,'DELETE').catch(()=>{});
      throw Error('Credential was not stored; generated key revoked.');
    }
  }
  console.log(JSON.stringify({project,account:email,role,createdAccount,createdKey,
    credentialFile:target,inventoryAccessGranted:false,billingChanged:false}));
}
main().catch(error=>{console.error(error.message.split('\n')[0]);process.exitCode=1;});
