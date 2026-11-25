'use strict';
const fs=require('node:fs'),path=require('node:path');
const lib=process.argv[2],mode=process.argv[3]||'backup';
async function main(){
 const {getProjectDefaultAccount}=require(path.join(lib,'auth.js'));
 const {requireAuth}=require(path.join(lib,'requireAuth.js'));
 const {listAllReleases,getRulesetContent}=require(path.join(lib,'gcp/rules.js'));
 const project='warehouseqa-8c262';
 await requireAuth({project,...getProjectDefaultAccount(process.cwd())});
 const releases=await listAllReleases(project);
 const release=releases.find(r=>r.name===`projects/${project}/releases/cloud.firestore`||r.name===`projects/${project}/releases/cloud.firestore/(default)`);
 if(!release)throw Error('Current Firestore release missing');
 const files=await getRulesetContent(release.rulesetName);
 const local=fs.readFileSync('firestore.rules','utf8');
 const matches=files.some(f=>f.content.trim()===local.trim());
 if(mode==='preflight'){
   const saved=JSON.parse(fs.readFileSync(path.join(__dirname,'cloud-rules-before.json'),'utf8'));
   if(JSON.stringify(saved.files)!==JSON.stringify(files))throw Error('Cloud rules changed after backup; preserve the external update before deploying');
   console.log(JSON.stringify({project,cloudRulesUnchangedSinceBackup:true}));return;
 }
 const file=path.join(__dirname,mode==='verify'?'cloud-rules-after.json':'cloud-rules-before.json');
 fs.writeFileSync(file,JSON.stringify({release,files},null,2)+'\n',{flag:'wx'});
 if(mode==='verify'&&!matches)throw Error('Deployed rules differ from tested source');
 console.log(JSON.stringify({project,release:release.name,matchesLocal:matches,backup:file}));
}
main().catch(e=>{console.error(String(e.message).split('\n')[0]);process.exitCode=1;});
