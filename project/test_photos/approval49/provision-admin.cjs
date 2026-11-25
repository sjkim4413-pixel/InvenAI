'use strict';
// Owner OAuth is used only by this explicit bootstrap. No password or tokens are logged or written.
const fs=require('node:fs'),path=require('node:path');
async function main(){
 const project='warehouseqa-8c262',email='admin@warehouse.com',lib=process.argv[2];
 const password=process.env.INVENAI_ADMIN_INITIAL_PASSWORD;
 if(!password || password.length<6)throw Error('Set INVENAI_ADMIN_INITIAL_PASSWORD for this explicit provisioning run.');
 const {getProjectDefaultAccount}=require(path.join(lib,'auth.js'));
 const {requireAuth}=require(path.join(lib,'requireAuth.js'));
 const {getAccessToken}=require(path.join(lib,'apiv2.js'));
 await requireAuth({project,...getProjectDefaultAccount(process.cwd())});
 const access=await getAccessToken();
 async function api(url,method='GET',body){
  const res=await fetch(url,{method,headers:{Authorization:`Bearer ${access}`,'Content-Type':'application/json'},
   body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(25000)});
  const data=await res.json();
  if(!res.ok){
   const reason=(data.error?.message||data.error?.status||'REJECTED').split(password).join('[redacted]').replace(/AIza[\w-]+/g,'[key]').slice(0,220);
   throw Error(`Provisioning ${new URL(url).pathname} HTTP ${res.status}: ${reason}`);
  }
  return data;
 }
 const root=`https://identitytoolkit.googleapis.com/v1/projects/${project}`;
 const existing=await api(root+'/accounts:lookup','POST',{email:[email]});
 let uid=existing.users?.[0]?.localId,created=false;
 if(uid){
  await api(root+'/accounts:update','POST',{localId:uid,password,displayName:'InvenAI 관리자',disableUser:false});
 }else{
  // The official Admin SDK uses this project-scoped endpoint without a client API key.
  const added=await api(root+'/accounts',
   'POST',{email,password,displayName:'InvenAI 관리자',disabled:false});
  uid=added.localId;created=true;
 }
 if(!uid)throw Error('Administrator UID missing.');
 const base=`https://firestore.googleapis.com/v1/projects/${project}/databases/(default)/documents`;
 const marker=await api(base+`/warehouseAdmins/${encodeURIComponent(uid)}?updateMask.fieldPaths=enabled&updateMask.fieldPaths=displayName`,
  'PATCH',{fields:{enabled:{booleanValue:true},displayName:{stringValue:'InvenAI 관리자'}}});
 if(marker.fields?.enabled?.booleanValue!==true)throw Error('Administrator marker verification failed.');
 const result={project,email,uid,created,adminEnabled:true,clientCredentialsStored:false,inventoryChanged:false};
 fs.writeFileSync(path.join(__dirname,'admin-provisioned.json'),JSON.stringify(result,null,2)+'\n');
 console.log(JSON.stringify(result));
}
main().catch(e=>{console.error(e.message.split('\n')[0]);process.exitCode=1;});
