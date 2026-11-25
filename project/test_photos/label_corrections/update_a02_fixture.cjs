'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const [cliRoot, mode='check'] = process.argv.slice(2);
assert(cliRoot && ['check','apply'].includes(mode));
const { Client } = require(path.join(cliRoot,'apiv2.js'));
const { getProjectDefaultAccount } = require(path.join(cliRoot,'auth.js'));
const { requireAuth } = require(path.join(cliRoot,'requireAuth.js'));
const project='warehouseqa-8c262', company='ABC-002', warehouse='AI 이미지 테스트 창고 01';
const database=`projects/${project}/databases/(default)`;
const documents=`${database}/documents`;
const name=`${documents}/inventory/ABC-002_SIM-AI01-P02`;
const metadata=JSON.parse(fs.readFileSync(path.join(__dirname,'A02-5F-edit.json'),'utf8').replace(/^\uFEFF/,''));
const original={photoLocationLabel:'',photoLabelIssue:'라벨 확인 불가',location:'AI01구역 P02-5단',
    rack:'P02',rackId:'AI01구역 P02',tier:'5단',photoSha256:metadata.original_sha256};
const patch={photoLocationLabel:'A02-5F',photoLabelIssue:'',location:'A02-5F',
    rack:'A02',rackId:'AI01구역 A02',tier:'5단',photoSha256:metadata.edited_sha256};
const encode=value=>({stringValue:value});
const client=new Client({urlPrefix:'https://firestore.googleapis.com',apiVersion:'v1',auth:true});
async function main(){
    const account=getProjectDefaultAccount(process.cwd());
    assert(account,'Firebase CLI login required');
    await requireAuth({project,user:account.user,tokens:account.tokens,nonInteractive:true});
    const info=(await client.get(database)).body;
    assert.equal(info.databaseEdition,'STANDARD');
    assert.equal(info.freeTier,true);
    const before=(await client.get(name)).body;
    for(const [field,expected] of Object.entries({companyCode:company,warehouse,itemCode:'SIM-AI01-P02',
        zone:'AI01구역',photoReference:'276ec63e-e08f-4915-b585-582e9529bdaa.png',dataSource:'SIMULATED_AI_IMAGE_ERP',status:'NORMAL'})){
        assert.equal(before.fields[field]?.stringValue,expected,field);
    }
    assert.equal(before.fields.isTestData?.booleanValue,true);
    assert.equal(Number(before.fields.quantity?.integerValue ?? before.fields.quantity?.doubleValue),1);
    const updated=Object.entries(patch).every(([key,value])=>before.fields[key]?.stringValue===value);
    if(!updated) {
        const previous=JSON.parse(fs.readFileSync(path.join(__dirname,'A02-5F-edit-v1.json'),'utf8').replace(/^\uFEFF/,''));
        const labelAlreadyCorrect=Object.entries(patch).filter(([key])=>key!=='photoSha256')
            .every(([key,value])=>before.fields[key]?.stringValue===value);
        if(labelAlreadyCorrect) assert.equal(before.fields.photoSha256.stringValue,previous.edited_sha256);
        else for(const [key,value] of Object.entries(original)) assert.equal(before.fields[key]?.stringValue,value,key);
    }
    const collision=await client.post(`${documents}:runQuery`,{structuredQuery:{
        from:[{collectionId:'inventory'}],where:{compositeFilter:{op:'AND',filters:[['companyCode',company],['warehouse',warehouse]]
          .map(([fieldPath,value])=>({fieldFilter:{field:{fieldPath},op:'EQUAL',value:encode(value)}}))}},limit:10001}});
    const records=collision.body.flatMap(row=>row.document?[row.document]:[]);
    assert.equal(records.length,20);
    assert(!records.some(row=>row.name!==name && row.fields.location?.stringValue==='A02-5F'),'Fixture location collision');
    console.log(JSON.stringify({stage:'preflight',mode,databaseEdition:info.databaseEdition,record:name.split('/').at(-1),pending:!updated,location:patch.location}));
    if(mode==='check') return;
    if(!updated){
        const backup=path.join(__dirname,'A02-5F-firestore-before.json');
        if(!fs.existsSync(backup)) fs.writeFileSync(backup,JSON.stringify(before,null,2)+'\n',{flag:'wx'});
        const response=await client.post(`${documents}:commit`,{writes:[{
            update:{name,fields:Object.fromEntries(Object.entries(patch).map(([key,value])=>[key,encode(value)]))},
            updateMask:{fieldPaths:Object.keys(patch)},currentDocument:{updateTime:before.updateTime}
        }]});
        assert.equal(response.body.writeResults.length,1);
    }
    const after=(await client.get(name)).body;
    const expected={...before.fields,...Object.fromEntries(Object.entries(patch).map(([key,value])=>[key,encode(value)]))};
    assert.deepEqual(after.fields,expected,'Unexpected changes outside the label fixture patch');
    fs.writeFileSync(path.join(__dirname,'A02-5F-firestore-after.json'),JSON.stringify(after,null,2)+'\n');
    console.log(JSON.stringify({stage:'verified',updated:updated?0:1,quantityUnchanged:true,statusUnchanged:true}));
}
main().catch(error=>{console.error(String(error.message).split('\n')[0].slice(0,500));process.exitCode=1;});
