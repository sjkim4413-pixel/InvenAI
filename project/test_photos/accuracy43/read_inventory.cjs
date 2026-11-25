'use strict';
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const [cliRoot,output]=process.argv.slice(2);
const {Client}=require(path.join(cliRoot,'apiv2.js'));
const {getProjectDefaultAccount}=require(path.join(cliRoot,'auth.js'));
const {requireAuth}=require(path.join(cliRoot,'requireAuth.js'));
const project='warehouseqa-8c262',company='ABC-002';
function decode(v){
  if('stringValue' in v)return v.stringValue;
  if('booleanValue' in v)return v.booleanValue;
  if('integerValue' in v)return Number(v.integerValue);
  if('doubleValue' in v)return v.doubleValue;
  if('arrayValue' in v)return (v.arrayValue.values??[]).map(decode);
  if('timestampValue' in v)return v.timestampValue;
  if('mapValue' in v)return Object.fromEntries(Object.entries(v.mapValue.fields??{}).map(([k,x])=>[k,decode(x)]));
  return null;
}
async function main(){
  const account=getProjectDefaultAccount(process.cwd());assert(account);
  await requireAuth({project,user:account.user,tokens:account.tokens,nonInteractive:true});
  const client=new Client({urlPrefix:'https://firestore.googleapis.com',apiVersion:'v1',auth:true});
  const database=`projects/${project}/databases/(default)`;
  const info=(await client.get(database)).body;
  assert.equal(info.databaseEdition,'STANDARD');assert.equal(info.freeTier,true);
  const response=await client.post(`${database}/documents:runQuery`,{structuredQuery:{
    from:[{collectionId:'inventory'}],where:{fieldFilter:{field:{fieldPath:'companyCode'},op:'EQUAL',value:{stringValue:company}}},limit:10001}});
  const records=response.body.flatMap(row=>row.document?[[row.document.name.split('/').at(-1),
    Object.fromEntries(Object.entries(row.document.fields).map(([k,v])=>[k,decode(v)]))]]:[]);
  assert(records.length<=10000);
  fs.writeFileSync(output,JSON.stringify(records,null,2)+'\n',{flag:'wx'});
  console.log(JSON.stringify({project,company,records:records.length,databaseEdition:info.databaseEdition}));
}
main().catch(error=>{console.error(String(error.message).split('\n')[0].slice(0,500));process.exitCode=1;});
