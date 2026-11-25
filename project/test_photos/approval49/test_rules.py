"""Admin approval integration and adversarial rules tests, strictly loopback-only."""
import json, urllib.request, urllib.error, uuid
from pathlib import Path

PROJECT='warehouseqa-8c262'
BASE=f'projects/{PROJECT}/databases/(default)/documents'
FS='http://127.0.0.1:18861/v1/'
AUTH='http://127.0.0.1:18862/identitytoolkit.googleapis.com/v1/'
checks=[]
def call(base,path,data=None,token=None,method=None):
    assert base in (FS,AUTH)
    headers={'Content-Type':'application/json'}
    if token: headers['Authorization']='Bearer '+token
    req=urllib.request.Request(base+path,data=json.dumps(data).encode() if data is not None else None,headers=headers,method=method)
    try:
        with urllib.request.urlopen(req,timeout=20) as r:return r.status,json.load(r)
    except urllib.error.HTTPError as e:return e.code,json.loads(e.read())
def enc(v):
    if v is None:return {'nullValue':None}
    if type(v) is bool:return {'booleanValue':v}
    if type(v) is int:return {'integerValue':str(v)}
    return {'stringValue':v}
def account(name):
    body=dict(email=f'{name}@approval49.test',password='Approval49LocalOnly!',returnSecureToken=True)
    code,r=call(AUTH,'accounts:signUp?key=local-test',body)
    if code!=200:code,r=call(AUTH,'accounts:signInWithPassword?key=local-test',body)
    assert code==200,(code,r)
    return r['localId'],r['idToken'],body['email']
def seed(path,data):
    code,r=call(FS,BASE+'/'+path,{'fields':{k:enc(v) for k,v in data.items()}},'owner','PATCH')
    assert code==200,(code,r)
def check(name,response,allowed):
    code,r=response
    assert (200<=code<300)==allowed,(name,code,r)
    checks.append(dict(case=name,status=code,allowed=allowed))
def write(path,data,times=(),update=False):
    result={'update':{'name':BASE+'/'+path,'fields':{k:enc(v) for k,v in data.items()}},
            'currentDocument':{'exists':update}}
    if update:result['updateMask']={'fieldPaths':list(data)}
    if times:
        result['updateTransforms']=[{'fieldPath':t,'setToServerValue':'REQUEST_TIME'} for t in times]
        if update:result['updateMask']['fieldPaths']+=list(times)
    return result
def commit(writes,token):return call(FS,BASE+':commit',{'writes':writes},token)
def request_data(account):
    return dict(userId=account[2].split('@')[0],email=account[2],companyCode='APPROVAL49',requestedRole='LOADER',
                status='PENDING',reviewedAt=None,reviewedBy='',assignedCompanyCode='',assignedRole='',rejectionReason='')
def signup(account,changes=None,times=('createdAt','submittedAt')):
    return write('signupRequests/'+account[0],{**request_data(account),**(changes or {})},times)
def approval(account,company='APPROVAL49',role='LOADER',admin=None):
    return write('signupRequests/'+account[0],dict(status='APPROVED',reviewedBy=admin or boss[0],
                 assignedCompanyCode=company,assignedRole=role,rejectionReason=''),('reviewedAt',),True)
def member(account,company='APPROVAL49',role='LOADER',admin=None):
    return write('warehouseMemberships/'+account[0],dict(companyCode=company,role=role,approvedBy=admin or boss[0]),('approvedAt',))

suffix=uuid.uuid4().hex[:8]
boss=account('admin-'+suffix); user=account('user-'+suffix); other=account('other-'+suffix)
seed('warehouseAdmins/'+boss[0],dict(enabled=True,displayName='Local administrator'))
check('anonymous cannot read admin marker',call(FS,BASE+'/warehouseAdmins/'+boss[0]),False)
code,result=call(FS,BASE+'/warehouseAdmins/'+user[0],token=user[1])
assert code==404,(code,result) # Allowed get of an absent document, not PERMISSION_DENIED.
checks.append(dict(case='ordinary user can read own missing admin marker',status=code,allowed=True))
check('ordinary user cannot write admin marker',commit([write('warehouseAdmins/'+user[0],dict(enabled=True))],user[1]),False)
check('admin cannot grant another admin',commit([write('warehouseAdmins/'+user[0],dict(enabled=True))],boss[1]),False)
check('owner cannot self-provision membership',commit([member(user,admin=user[0])],user[1]),False)
check('cross-user signup denied',commit([signup(user)],other[1]),False)
for name,changes in [('request ADMIN role',dict(requestedRole='ADMIN')),('invalid company',dict(companyCode='DEFAULT')),
                      ('spoof email',dict(email='someone@else.test')),('unknown field',dict(enabled=True)),
                      ('oversized identity',dict(userId='x'*129)),('owner chooses approved state',dict(status='APPROVED'))]:
    check(name,commit([signup(user,changes)],user[1]),False)
check('client timestamps denied',commit([signup(user,times=())],user[1]),False)
check('valid pending signup',commit([signup(user)],user[1]),True)
check('own pending readable',call(FS,BASE+'/signupRequests/'+user[0],token=user[1]),True)
check('other request private',call(FS,BASE+'/signupRequests/'+user[0],token=other[1]),False)
query={'structuredQuery':{'from':[{'collectionId':'signupRequests'}],
       'where':{'fieldFilter':{'field':{'fieldPath':'status'},'op':'EQUAL','value':enc('PENDING')}}}}
check('admin status-filter list',call(FS,BASE+':runQuery',query,boss[1]),True)
check('ordinary user list denied',call(FS,BASE+':runQuery',query,user[1]),False)
check('owner cannot approve self',commit([approval(user,admin=user[0]),member(user,admin=user[0])],user[1]),False)
check('approve without membership denied',commit([approval(user)],boss[1]),False)
check('membership without approval denied',commit([member(user)],boss[1]),False)
check('mismatched role denied',commit([approval(user),member(user,role='QA')],boss[1]),False)
check('mismatched company denied',commit([approval(user),member(user,company='OTHER49')],boss[1]),False)
check('admin elevation via approval denied',commit([approval(user,role='ADMIN'),member(user,role='ADMIN')],boss[1]),False)
check('owner edits pending request',commit([write('signupRequests/'+user[0],dict(companyCode='APPROVAL49-EDIT'),('submittedAt',),True)],user[1]),True)
check('admin atomic approval with assigned company',commit([approval(user),member(user)],boss[1]),True)
check('approved owner cannot resubmit',commit([write('signupRequests/'+user[0],dict(status='PENDING',reviewedAt=None,reviewedBy='',assignedCompanyCode='',assignedRole=''),('submittedAt',),True)],user[1]),False)
check('membership immutable to admin',commit([write('warehouseMemberships/'+user[0],dict(role='QA'),(),True)],boss[1]),False)
check('approved worker reads own membership',call(FS,BASE+'/warehouseMemberships/'+user[0],token=user[1]),True)
seed('inventory/APPROVAL49_ONE',dict(companyCode='APPROVAL49',itemCode='ONE',itemName='One',quantity=3,location='A01-1F',status='NORMAL'))
seed('inventory/OTHER49_ONE',dict(companyCode='OTHER49',itemCode='ONE',itemName='Other',quantity=3,location='A01-1F',status='NORMAL'))
check('approved worker company stock accessible',call(FS,BASE+'/inventory/APPROVAL49_ONE',token=user[1]),True)
check('approved worker other company blocked',call(FS,BASE+'/inventory/OTHER49_ONE',token=user[1]),False)
check('admin marker alone grants no stock access',call(FS,BASE+'/inventory/APPROVAL49_ONE',token=boss[1]),False)
check('second request',commit([signup(other)],other[1]),True)
reject=write('signupRequests/'+other[0],dict(status='REJECTED',reviewedBy=boss[0],rejectionReason='Confirm company'),('reviewedAt',),True)
check('admin rejects without membership',commit([reject],boss[1]),True)
check('rejected account has no stock access',call(FS,BASE+'/inventory/APPROVAL49_ONE',token=other[1]),False)
reset=dict(status='PENDING',reviewedAt=None,reviewedBy='',rejectionReason='',companyCode='APPROVAL49',requestedRole='QA')
check('rejected owner resubmits',commit([write('signupRequests/'+other[0],reset,('submittedAt',),True)],other[1]),True)
seed('warehouseAdmins/'+boss[0],dict(enabled=False))
check('revoked admin cannot approve',commit([approval(other,role='QA'),member(other,role='QA')],boss[1]),False)
seed('warehouseAdmins/'+boss[0],dict(enabled=True))

# Stable fixtures for the Android UI transaction tests. No production accounts.
for name in ['ui-admin','ui-applicant','ui-rejected']:
    a=account(name)
    call(FS,BASE+'/signupRequests/'+a[0],token='owner',method='DELETE')
    call(FS,BASE+'/warehouseMemberships/'+a[0],token='owner',method='DELETE')
    if name=='ui-admin': seed('warehouseAdmins/'+a[0],dict(enabled=True))
summary=dict(emulatorOnly=True,passed=True,cases=len(checks),checks=checks)
Path(__file__).with_name('rules-verification.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps({k:v for k,v in summary.items() if k!='checks'}))
