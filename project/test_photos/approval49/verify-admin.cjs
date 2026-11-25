'use strict';
// Exercise exactly the client sign-in, own-admin check, and pending-list permissions.
// Never log credentials, refresh tokens, ID tokens, or account response bodies.
const fs=require('node:fs'),path=require('node:path');
async function main(){
 const project='warehouseqa-8c262',email='admin@warehouse.com';
 const password=process.env.INVENAI_ADMIN_INITIAL_PASSWORD;
 if(!password)throw Error('Administrator password input missing.');
 const config=JSON.parse(fs.readFileSync('app/google-services.json','utf8'));
 if(config.project_info.project_id!==project)throw Error('Unexpected client project.');
 const key=config.client[0].api_key[0].current_key;
 async function api(url,body,token){
  const res=await fetch(url,{method:body?'POST':'GET',headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},
   body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(25000)});
  const data=await res.json();
  if(!res.ok)throw Error(`Client verification ${new URL(url).pathname} HTTP ${res.status}`);
  return data;
 }
 const signed=await api(`https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${encodeURIComponent(key)}`,
  {email,password,returnSecureToken:true});
 const provisioned=JSON.parse(fs.readFileSync(path.join(__dirname,'admin-provisioned.json'),'utf8'));
 if(signed.localId!==provisioned.uid)throw Error('Unexpected administrator UID.');
 const base=`https://firestore.googleapis.com/v1/projects/${project}/databases/(default)/documents`;
 const marker=await api(base+`/warehouseAdmins/${encodeURIComponent(signed.localId)}`,undefined,signed.idToken);
 if(marker.fields?.enabled?.booleanValue!==true)throw Error('Client cannot verify administrator authority.');
 const pending=await api(base+':runQuery',{structuredQuery:{from:[{collectionId:'signupRequests'}],where:{fieldFilter:{
  field:{fieldPath:'status'},op:'EQUAL',value:{stringValue:'PENDING'}}}}},signed.idToken);
 const result={project,loginVerified:true,adminMarkerVerified:true,pendingQueryAllowed:true,
  pendingCount:pending.filter(x=>x.document).length,productionInventoryWrites:0};
 fs.writeFileSync(path.join(__dirname,'cloud-admin-verification.json'),JSON.stringify(result,null,2)+'\n');
 console.log(JSON.stringify(result));
}
main().catch(e=>{console.error(e.message.split('\n')[0]);process.exitCode=1;});
