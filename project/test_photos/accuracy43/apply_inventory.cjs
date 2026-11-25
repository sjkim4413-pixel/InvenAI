'use strict';
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const [cliRoot,mode='check']=process.argv.slice(2);
assert(cliRoot && ['check','apply'].includes(mode));
const {Client}=require(path.join(cliRoot,'apiv2.js'));
const {getProjectDefaultAccount}=require(path.join(cliRoot,'auth.js'));
const {requireAuth}=require(path.join(cliRoot,'requireAuth.js'));
const plan=JSON.parse(fs.readFileSync(path.join(__dirname,'inventory_plan.json'),'utf8'));
assert.equal(plan.project,'warehouseqa-8c262');assert.equal(plan.company,'ABC-002');assert.equal(plan.records.length,109);
assert.equal(new Set(plan.records.map(r=>r.id)).size,109);
const database=`projects/${plan.project}/databases/(default)`,documents=`${database}/documents`;
const allowed=new Set(['photoLocationLabel','photoLabelIssue','photoSha256','location','rack','rackId','tier','side']);
const warehouses=new Set(['AI 이미지 테스트 창고 01','AI 이미지 테스트 창고 02','AI 이미지 테스트 창고 03','AI 실사 추가 테스트 창고']);
const targets=plan.records.map(record=>{
  assert.match(record.id,/^ABC-002_SIM-(AI0[1-3]-P\d{2}|QA[E-G]\d{2}-[1-9]F-\d{2})$/);
  assert.equal(record.before.companyCode,plan.company);assert(warehouses.has(record.before.warehouse));
  assert.equal(record.before.isTestData,true);assert.equal(record.before.dataSource,'SIMULATED_AI_IMAGE_ERP');
  assert.equal(record.before.quantity,1);assert.equal(record.before.status,'NORMAL');
  assert.equal(record.id,`${plan.company}_${record.before.itemCode}`);
  assert(Object.keys(record.patch).every(key=>allowed.has(key)));
  assert(Object.values(record.patch).every(value=>typeof value==='string'));
  assert.equal(record.patch.location,record.patch.photoLocationLabel);assert.equal(record.patch.photoLabelIssue,'');
  const content=fs.readFileSync(path.join(__dirname,'../../test_image_ai',record.before.photoReference));
  assert.equal(crypto.createHash('sha256').update(content).digest('hex'),record.patch.photoSha256);
  return {...record,name:`${documents}/inventory/${record.id}`};
});
function decode(v){
  if('stringValue' in v)return v.stringValue;if('booleanValue' in v)return v.booleanValue;
  if('integerValue' in v)return Number(v.integerValue);if('doubleValue' in v)return v.doubleValue;
  if('timestampValue' in v)return v.timestampValue;
  if('arrayValue' in v)return (v.arrayValue.values??[]).map(decode);
  if('mapValue' in v)return Object.fromEntries(Object.entries(v.mapValue.fields??{}).map(([k,x])=>[k,decode(x)]));
  return null;
}
const data=doc=>Object.fromEntries(Object.entries(doc.fields).map(([k,v])=>[k,decode(v)]));
async function main(){
  const account=getProjectDefaultAccount(process.cwd());assert(account);
  await requireAuth({project:plan.project,user:account.user,tokens:account.tokens,nonInteractive:true});
  const client=new Client({urlPrefix:'https://firestore.googleapis.com',apiVersion:'v1',auth:true});
  const info=(await client.get(database)).body;assert.equal(info.databaseEdition,'STANDARD');assert.equal(info.freeTier,true);
  async function readTargets(){
    const response=await client.post(`${documents}:batchGet`,{documents:targets.map(t=>t.name)});
    const found=new Map(response.body.flatMap(row=>row.found?[[row.found.name,row.found]]:[]));
    assert.equal(found.size,109);return found;
  }
  const before=await readTargets();const writes=[];
  for(const target of targets){
    const doc=before.get(target.name),actual=data(doc),expected={...target.before,...target.patch};
    if(JSON.stringify(actual)!==JSON.stringify(expected)){
      try{assert.deepEqual(actual,expected);continue;}catch{}
      assert.deepEqual(actual,target.before,`Fixture changed since baseline: ${target.id}`);
      writes.push({update:{name:target.name,fields:Object.fromEntries(Object.entries(target.patch).map(([k,v])=>[k,{stringValue:v}]))},
        updateMask:{fieldPaths:Object.keys(target.patch)},currentDocument:{updateTime:doc.updateTime}});
    }
  }
  const company=await client.post(`${documents}:runQuery`,{structuredQuery:{from:[{collectionId:'inventory'}],
    select:{fields:['companyCode','warehouse','zone','location'].map(fieldPath=>({fieldPath}))},
    where:{fieldFilter:{field:{fieldPath:'companyCode'},op:'EQUAL',value:{stringValue:plan.company}}},limit:10001}});
  const entries=company.body.flatMap(row=>row.document?[row.document]:[]);assert(entries.length<=10000);
  const byName=new Map(targets.map(target=>[target.name,target]));const occupied=new Set();
  for(const doc of entries){
    const current=data(doc),target=byName.get(doc.name);
    const key=JSON.stringify([current.warehouse,current.zone??'',target?target.patch.location:current.location]);
    assert(!occupied.has(key),`Test location collision: ${key}`);occupied.add(key);
  }
  console.log(JSON.stringify({stage:'preflight',mode,companyRecords:entries.length,targets:targets.length,pending:writes.length,edition:info.databaseEdition}));
  if(mode==='check')return;
  if(writes.length){
    const backup=path.join(__dirname,'inventory_before_raw.json');
    if(!fs.existsSync(backup))fs.writeFileSync(backup,JSON.stringify([...before.values()],null,2)+'\n',{flag:'wx'});
    const response=await client.post(`${documents}:commit`,{writes});assert.equal(response.body.writeResults.length,writes.length);
  }
  const after=await readTargets();const readback=[];
  for(const target of targets){
    const actual=data(after.get(target.name));assert.deepEqual(actual,{...target.before,...target.patch},target.id);
    readback.push([target.id,actual]);
  }
  fs.writeFileSync(path.join(__dirname,'inventory_readback.json'),JSON.stringify(readback,null,2)+'\n');
  console.log(JSON.stringify({stage:'verified',updated:writes.length,verified:readback.length,quantityAndStatusUnchanged:true}));
}
main().catch(error=>{console.error(String(error.message).split('\n')[0].slice(0,600));process.exitCode=1;});
